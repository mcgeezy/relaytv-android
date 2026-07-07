package pro.relaytv

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A [SimpleBasePlayer] that mirrors playback happening on the RelayTV server.
 * It never plays audio locally: state comes from polling /status and every
 * transport control is forwarded to the server over HTTP via [Listener].
 */
@UnstableApi
class RelayRemotePlayer(
    looper: Looper,
    private val listener: Listener,
) : SimpleBasePlayer(looper) {

    interface Listener {
        fun onSetPaused(paused: Boolean)
        fun onNext()
        fun onPrevious()
        fun onSeekTo(seconds: Double)
        fun onStop()
        fun onSetVolume(percent: Int)
    }

    private var status: RemoteStatus = RemoteStatus.IDLE
    private var serverName: String = "RelayTV"
    private var artworkBytes: ByteArray? = null

    // Anchor for extrapolating the playback position between polls.
    private var anchorPositionMs: Long = 0
    private var anchorElapsedRealtime: Long = SystemClock.elapsedRealtime()

    /** Must be called on the application looper. */
    fun updateStatus(newStatus: RemoteStatus, newServerName: String, artwork: ByteArray?) {
        status = newStatus
        serverName = newServerName
        artworkBytes = artwork
        setPositionAnchor((newStatus.positionSec ?: 0.0) * 1000.0)
        invalidateState()
    }

    fun isActive(): Boolean = status.active

    private fun setPositionAnchor(positionMs: Double) {
        anchorPositionMs = positionMs.toLong().coerceAtLeast(0)
        anchorElapsedRealtime = SystemClock.elapsedRealtime()
    }

    private fun extrapolatedPositionMs(): Long {
        var pos = anchorPositionMs
        if (status.playing && !status.paused) {
            pos += SystemClock.elapsedRealtime() - anchorElapsedRealtime
        }
        val durationMs = status.durationSec?.let { (it * 1000).toLong() }
        if (durationMs != null && durationMs > 0) {
            pos = pos.coerceAtMost(durationMs)
        }
        return pos.coerceAtLeast(0)
    }

    override fun getState(): State {
        val s = status
        if (!s.active) {
            return State.Builder()
                .setAvailableCommands(Player.Commands.EMPTY)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }

        val durationUs = s.durationSec
            ?.takeIf { it > 0 }
            ?.let { (it * C.MICROS_PER_SECOND).toLong() }
            ?: C.TIME_UNSET
        val seekable = durationUs != C.TIME_UNSET

        val metadata = MediaMetadata.Builder()
            .setTitle(s.title ?: "Playing on $serverName")
            .setArtist(serverName)
            .apply {
                artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()

        // Placeholder neighbours let controllers issue next/previous, which we
        // forward to the server's queue instead of a local playlist.
        val previousItem = MediaItemData.Builder("previous")
            .setMediaItem(MediaItem.Builder().setMediaId("previous").build())
            .build()
        val currentItem = MediaItemData.Builder("current")
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId("current")
                    .setMediaMetadata(metadata)
                    .build()
            )
            .setDurationUs(durationUs)
            .setIsSeekable(seekable)
            .build()
        val nextItem = MediaItemData.Builder("next")
            .setMediaItem(MediaItem.Builder().setMediaId("next").build())
            .build()

        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, seekable)
            .addIf(Player.COMMAND_SEEK_BACK, seekable)
            .addIf(Player.COMMAND_SEEK_FORWARD, seekable)
            .addIf(Player.COMMAND_GET_DEVICE_VOLUME, s.volumePercent != null)
            .addIf(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, s.volumePercent != null)
            .addIf(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS, s.volumePercent != null)
            .build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(previousItem, currentItem, nextItem))
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(!s.paused, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs { extrapolatedPositionMs() }
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .setMaxSeekToPreviousPositionMs(3_000)

        if (s.volumePercent != null) {
            builder
                .setDeviceInfo(
                    DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                        .setMinVolume(0)
                        .setMaxVolume(100)
                        .build()
                )
                .setDeviceVolume(s.volumePercent)
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        // Optimistic local update; the next poll confirms the real state.
        status = status.copy(paused = !playWhenReady, playing = true)
        setPositionAnchor(extrapolatedPositionMs().toDouble())
        listener.onSetPaused(!playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when {
            mediaItemIndex > 1 -> listener.onNext()
            mediaItemIndex < 1 -> listener.onPrevious()
            else -> {
                val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0)
                setPositionAnchor(target.toDouble())
                listener.onSeekTo(target / 1000.0)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        status = RemoteStatus.IDLE
        listener.onStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val vol = deviceVolume.coerceIn(0, 100)
        status = status.copy(volumePercent = vol)
        listener.onSetVolume(vol)
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume((status.volumePercent ?: 0) + 5, flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        handleSetDeviceVolume((status.volumePercent ?: 0) - 5, flags)

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
