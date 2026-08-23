package pro.relaytv

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * Publishes a MediaSession that mirrors playback on the active RelayTV server,
 * giving lock screen / quick settings media controls for the TV. All transport
 * actions are forwarded to the server's HTTP API; nothing plays locally.
 *
 * The service bootstraps from GET /status, prefers realtime push delivery,
 * and polls only while WebSocket and SSE are unavailable.
 */
@UnstableApi
class MediaControlService : MediaSessionService() {

    private val handler = Handler(Looper.getMainLooper())
    private var player: RelayRemotePlayer? = null
    private var session: MediaSession? = null
    private var realtimeClient: RelayRealtimeClient? = null
    private var realtimeIdentity: String? = null
    private var realtimeOwner = 0L
    private var pushHealthy = false
    private var pollCall: Call? = null
    private var pollGeneration = 0L
    private val mediaState = MediaStatusState()

    private var lastActiveAt = SystemClock.elapsedRealtime()
    private var artworkUrl: String? = null
    private var artworkBytes: ByteArray? = null

    private val pollRunnable = Runnable { poll() }
    private val idleStopRunnable = Runnable { stopIfStillIdle() }
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                pushHealthy = false
                realtimeClient?.restart()?.let { realtimeOwner = it }
                schedulePoll(0)
            }
        }
    }

    companion object {
        private const val POLL_PLAYING_MS = 3_000L
        private const val POLL_PAUSED_MS = 5_000L
        private const val POLL_IDLE_MS = 10_000L
        private const val POLL_AFTER_COMMAND_MS = 700L
        private const val IDLE_STOP_MS = 5 * 60_000L
        private const val STALE_STATUS_GRACE_MS = 30_000L
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

        realtimeClient = RelayRealtimeClient(listener = object : RelayRealtimeClient.Listener {
            override fun onTransportChanged(
                owner: Long,
                identity: String,
                transport: RelayRealtimeClient.Transport?,
            ) {
                handler.post {
                    if (realtimeOwner != owner || realtimeIdentity != identity) return@post
                    pushHealthy = transport != null
                    if (pushHealthy) {
                        handler.removeCallbacks(pollRunnable)
                    } else if (pollCall == null) {
                        schedulePoll(0)
                    }
                }
            }

            override fun onEvent(owner: Long, identity: String, event: String, data: JSONObject) {
                handler.post {
                    if (realtimeOwner != owner || realtimeIdentity != identity) return@post
                    applyRealtimeEvent(event, data)
                }
            }

            override fun onAuthoritativeRefreshRequired(owner: Long, identity: String) {
                handler.post {
                    if (
                        realtimeOwner == owner &&
                        realtimeIdentity == identity &&
                        pollCall == null
                    ) schedulePoll(0)
                }
            }
        })
        registerNetworkCallback()

        schedulePoll(0)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!AppSettings.isMediaControlsEnabled(this)) {
            realtimeClient?.stop()
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
        pollGeneration += 1
        pollCall?.cancel()
        pollCall = null
        realtimeIdentity = null
        realtimeOwner = 0L
        pushHealthy = false
        realtimeClient?.close()
        realtimeClient = null
        unregisterNetworkCallback()
        session?.let {
            removeSession(it)
            it.release()
        }
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun schedulePoll(delayMs: Long) {
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, delayMs)
    }

    private fun poll() {
        if (!AppSettings.isMediaControlsEnabled(this)) {
            realtimeClient?.stop()
            stopSelf()
            return
        }
        val host = HostStore.getActiveHost(this)
        val base = host?.let { HostStore.normalizeBaseUrl(it.baseUrl) }
        if (host == null || base.isNullOrBlank()) {
            retireRealtime()
            clearMediaState("RelayTV")
            renderStatus(RemoteStatus.IDLE, "RelayTV", base = null)
            return
        }
        ensureRealtime(host, base)
        val owner = ++pollGeneration
        val stateOwner = mediaState.beginPoll()
        pollCall?.cancel()
        val call = Net.client.newCall(Net.get("$base/status", host.apiToken))
        pollCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post {
                    if (owner != pollGeneration || pollCall !== call) return@post
                    pollCall = null
                    if (!mediaState.isCurrent(stateOwner)) {
                        if (!pushHealthy) schedulePoll(0)
                        return@post
                    }
                    handlePollFailure(host.name.ifBlank { "RelayTV" }, base)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val status = response.use { resp ->
                    if (resp.isSuccessful) RemoteStatus.parse(resp.body?.string().orEmpty()) else null
                }
                handler.post {
                    if (owner != pollGeneration || pollCall !== call) return@post
                    pollCall = null
                    if (!mediaState.isCurrent(stateOwner)) {
                        if (!pushHealthy) schedulePoll(0)
                        return@post
                    }
                    if (status == null) {
                        handlePollFailure(host.name.ifBlank { "RelayTV" }, base)
                    } else {
                        if (
                            mediaState.acceptPoll(
                                stateOwner,
                                status,
                                SystemClock.elapsedRealtime(),
                            )
                        ) {
                            renderStatus(status, host.name.ifBlank { "RelayTV" }, base)
                        }
                    }
                }
            }
        })
    }

    private fun serverName(): String =
        HostStore.getActiveHost(this)?.name?.ifBlank { "RelayTV" } ?: "RelayTV"

    private fun renderStatus(status: RemoteStatus, serverName: String, base: String?) {
        val player = player ?: return

        if (status.active) {
            lastActiveAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(idleStopRunnable)
        } else {
            val remaining = IDLE_STOP_MS - (SystemClock.elapsedRealtime() - lastActiveAt)
            if (remaining <= 0) {
                player.updateStatus(RemoteStatus.IDLE, serverName, null)
                stopSelf()
                return
            }
            handler.removeCallbacks(idleStopRunnable)
            handler.postDelayed(idleStopRunnable, remaining)
        }

        ensureArtwork(base, status.thumbnail)
        player.updateStatus(status, serverName, artworkBytes)

        if (!pushHealthy) {
            val delay = when {
                status.playing && !status.paused -> POLL_PLAYING_MS
                status.active -> POLL_PAUSED_MS
                else -> POLL_IDLE_MS
            }
            schedulePoll(delay)
        }
    }

    private fun handlePollFailure(serverName: String, base: String) {
        val retained = mediaState.retained(SystemClock.elapsedRealtime(), STALE_STATUS_GRACE_MS)
        if (retained != null) {
            renderStatus(retained, serverName, base)
        } else {
            mediaState.reset()
            renderStatus(RemoteStatus.IDLE, serverName, base)
        }
    }

    private fun applyRealtimeEvent(event: String, data: JSONObject) {
        val host = HostStore.getActiveHost(this) ?: return
        val base = HostStore.normalizeBaseUrl(host.baseUrl) ?: return
        val name = host.name.ifBlank { "RelayTV" }
        when (event) {
            "status" -> {
                val status = mediaState.acceptRealtime(
                    RemoteStatus.parse(data),
                    SystemClock.elapsedRealtime(),
                )
                renderStatus(status, name, base)
            }
            "playback" -> {
                val status = mediaState.mergeRealtimePlayback(data, SystemClock.elapsedRealtime())
                if (status == null) {
                    schedulePoll(0)
                } else {
                    renderStatus(status, name, base)
                }
            }
            "queue", "jellyfin" -> schedulePoll(POLL_AFTER_COMMAND_MS)
            "hello", "ping" -> Unit
        }
    }

    private fun ensureRealtime(host: RelayHost, base: String) {
        val identity = "${host.id}|$base|${host.apiToken}"
        if (realtimeIdentity == identity) return
        pollGeneration += 1
        pollCall?.cancel()
        pollCall = null
        clearMediaState(host.name.ifBlank { "RelayTV" }, refreshIdleDeadline = true)
        realtimeIdentity = identity
        pushHealthy = false
        realtimeOwner = realtimeClient?.start(
            RelayRealtimeClient.Config(
                identity = identity,
                baseUrl = base,
                apiToken = host.apiToken,
            )
        ) ?: 0L
    }

    private fun retireRealtime() {
        pollGeneration += 1
        pollCall?.cancel()
        pollCall = null
        realtimeIdentity = null
        realtimeOwner = 0L
        pushHealthy = false
        realtimeClient?.stop()
    }

    private fun clearMediaState(serverName: String, refreshIdleDeadline: Boolean = false) {
        mediaState.reset()
        artworkUrl = null
        artworkBytes = null
        if (refreshIdleDeadline) lastActiveAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(idleStopRunnable)
        player?.updateStatus(RemoteStatus.IDLE, serverName, null)
    }

    private fun stopIfStillIdle() {
        val current = mediaState.status ?: return
        if (!current.active && SystemClock.elapsedRealtime() - lastActiveAt >= IDLE_STOP_MS) {
            player?.updateStatus(RemoteStatus.IDLE, serverName(), null)
            stopSelf()
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
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
                            mediaState.status?.let { renderStatus(it, serverName(), base) }
                        }
                    }
                }
            }
        })
    }

    private fun post(path: String, json: String = "{}") {
        val host = HostStore.getActiveHost(this) ?: return
        val base = HostStore.normalizeBaseUrl(host.baseUrl) ?: return
        Net.client.newCall(Net.postJson(base + path, json, host.apiToken)).enqueue(object : Callback {
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
