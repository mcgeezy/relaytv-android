package pro.relaytv

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * Publishes a MediaSession that mirrors playback on the active RelayTV server,
 * giving lock screen / quick settings media controls for the TV. All transport
 * actions are forwarded to the server's HTTP API; nothing plays locally.
 *
 * The service polls GET /status while running and stops itself after the
 * server has been idle for a while (MainActivity restarts it while the app
 * is in the foreground).
 */
@UnstableApi
class MediaControlService : MediaSessionService() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: RelayRemotePlayer? = null
    private var session: MediaSession? = null

    private var lastActiveAt = SystemClock.elapsedRealtime()
    private var artworkUrl: String? = null
    private var artworkBytes: ByteArray? = null

    private val pollRunnable = Runnable { poll() }

    companion object {
        private const val POLL_PLAYING_MS = 3_000L
        private const val POLL_PAUSED_MS = 5_000L
        private const val POLL_IDLE_MS = 10_000L
        private const val POLL_AFTER_COMMAND_MS = 700L
        private const val IDLE_STOP_MS = 5 * 60_000L
        private const val MAX_ARTWORK_BYTES = 3L * 1024 * 1024
    }

    private val playerListener = object : RelayRemotePlayer.Listener {
        override fun onSetPaused(paused: Boolean) = post(if (paused) "/pause" else "/resume")
        override fun onNext() = post("/next")
        override fun onPrevious() = post("/previous")
        override fun onSeekTo(seconds: Double) = post("/seek_abs", """{"sec":$seconds}""")
        override fun onStop() = post("/stop")
        override fun onSetVolume(percent: Int) = post("/volume", """{"set":$percent}""")
    }

    override fun onCreate() {
        super.onCreate()
        val player = RelayRemotePlayer(Looper.getMainLooper(), playerListener)
        this.player = player

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val session = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        this.session = session
        // Nothing external binds to this service (playback starts on the server),
        // so the session must be registered explicitly for the media notification
        // manager to track it.
        addSession(session)

        schedulePoll(0)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!AppSettings.isMediaControlsEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Keep the idle timer fresh while the app keeps nudging us.
        lastActiveAt = SystemClock.elapsedRealtime()
        schedulePoll(0)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun activeBase(): String? = HostStore.getActiveBaseUrl(this)?.trimEnd('/')

    private fun schedulePoll(delayMs: Long) {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, delayMs)
    }

    private fun poll() {
        if (!AppSettings.isMediaControlsEnabled(this)) {
            stopSelf()
            return
        }
        val base = activeBase()
        if (base.isNullOrBlank()) {
            applyStatus(RemoteStatus.IDLE, "RelayTV", base = null)
            return
        }
        Net.client.newCall(Net.get("$base/status")).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { applyStatus(RemoteStatus.IDLE, serverName(), base) }
            }

            override fun onResponse(call: Call, response: Response) {
                val status = response.use { resp ->
                    if (!resp.isSuccessful) {
                        RemoteStatus.IDLE
                    } else {
                        RemoteStatus.parse(resp.body?.string().orEmpty())
                    }
                }
                handler.post { applyStatus(status, serverName(), base) }
            }
        })
    }

    private fun serverName(): String =
        HostStore.getActiveHost(this)?.name?.ifBlank { "RelayTV" } ?: "RelayTV"

    private fun applyStatus(status: RemoteStatus, serverName: String, base: String?) {
        val player = player ?: return

        if (status.active) {
            lastActiveAt = SystemClock.elapsedRealtime()
        } else if (SystemClock.elapsedRealtime() - lastActiveAt > IDLE_STOP_MS) {
            player.updateStatus(RemoteStatus.IDLE, serverName, null)
            stopSelf()
            return
        }

        ensureArtwork(base, status.thumbnail)
        player.updateStatus(status, serverName, artworkBytes)

        val delay = when {
            status.playing && !status.paused -> POLL_PLAYING_MS
            status.active -> POLL_PAUSED_MS
            else -> POLL_IDLE_MS
        }
        schedulePoll(delay)
    }

    /** Resolve the thumbnail to an absolute URL and fetch it once per URL change. */
    private fun ensureArtwork(base: String?, thumbnail: String?) {
        val absolute = when {
            thumbnail.isNullOrBlank() -> null
            thumbnail.startsWith("http://") || thumbnail.startsWith("https://") -> thumbnail
            base.isNullOrBlank() -> null
            thumbnail.startsWith("/") -> base + thumbnail
            else -> "$base/$thumbnail"
        }
        if (absolute == artworkUrl) return
        artworkUrl = absolute
        artworkBytes = null
        if (absolute == null) return

        Net.client.newCall(Net.get(absolute)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* no artwork */ }

            override fun onResponse(call: Call, response: Response) {
                val bytes = response.use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null || body.contentLength() > MAX_ARTWORK_BYTES) {
                        null
                    } else {
                        try {
                            body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_ARTWORK_BYTES }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
                if (bytes != null) {
                    handler.post {
                        if (artworkUrl == absolute) {
                            artworkBytes = bytes
                            // Re-render the notification with artwork right away.
                            schedulePoll(0)
                        }
                    }
                }
            }
        })
    }

    private fun post(path: String, json: String = "{}") {
        val base = activeBase() ?: return
        Net.client.newCall(Net.postJson(base + path, json)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { schedulePoll(POLL_AFTER_COMMAND_MS) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                handler.post { schedulePoll(POLL_AFTER_COMMAND_MS) }
            }
        })
    }
}
