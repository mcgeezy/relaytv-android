package pro.relaytv

import kotlin.math.abs

/** Pure lifecycle decisions shared by the media-control service and unit tests. */
internal fun shouldScheduleMediaPoll(
    identityChanged: Boolean,
    pushHealthy: Boolean,
    pollInFlight: Boolean,
    hasAuthoritativeStatus: Boolean,
): Boolean = identityChanged || (
    !pollInFlight && (!pushHealthy || !hasAuthoritativeStatus)
)

internal fun realtimeCallbackMatchesActiveHost(
    callbackOwner: Long,
    activeOwner: Long,
    callbackIdentity: String,
    connectionIdentity: String?,
    selectedIdentity: String?,
): Boolean = callbackOwner == activeOwner &&
    callbackIdentity == connectionIdentity &&
    callbackIdentity == selectedIdentity

/**
 * Determines whether a new [RemoteStatus] requires invalidating the Media3 [SimpleBasePlayer]
 * state (which rebuilds [androidx.media3.common.MediaMetadata] and triggers SystemUI binder IPC).
 *
 * Because [RelayRemotePlayer] extrapolates playback progress locally from an elapsed-realtime
 * anchor, routine 1x playback position ticks do not need to wake SystemUI unless transport state,
 * metadata, volume, seekability, or position drift (>= [maxPositionDriftMs]) changed.
 */
internal fun shouldInvalidatePlayerState(
    previous: RemoteStatus,
    next: RemoteStatus,
    extrapolatedPositionMs: Long,
    maxPositionDriftMs: Long = 1_500L,
): Boolean {
    if (!previous.active && !next.active) return false
    if (previous.active != next.active) return true
    if (previous.playing != next.playing || previous.paused != next.paused) return true
    if (previous.title != next.title || previous.thumbnail != next.thumbnail) return true
    if (previous.effectiveDurationSec != next.effectiveDurationSec) return true
    if (previous.isSeekable != next.isSeekable) return true
    if (previous.volumePercent != next.volumePercent) return true
    if (previous.dvr != next.dvr) return true

    val prevPos = previous.positionSec
    val nextPos = next.positionSec
    if (prevPos == null && nextPos == null) return false
    if ((prevPos == null) != (nextPos == null)) return true

    val nextPositionMs = ((nextPos ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
    return abs(nextPositionMs - extrapolatedPositionMs) >= maxPositionDriftMs
}

/**
 * Computes the `/status` poll interval when realtime push is unavailable.
 * Applies exponential backoff when the server is idle/unreachable across consecutive poll failures.
 */
internal fun pollDelayForStatusMs(
    status: RemoteStatus,
    consecutiveFailures: Int,
    playingDelayMs: Long = 3_000L,
    pausedDelayMs: Long = 5_000L,
    idleDelayMs: Long = 10_000L,
    maxBackoffDelayMs: Long = 60_000L,
): Long = when {
    status.playing && !status.paused -> playingDelayMs
    status.active -> pausedDelayMs
    consecutiveFailures <= 0 -> idleDelayMs
    else -> {
        val shift = (consecutiveFailures - 1).coerceIn(0, 3)
        minOf(maxBackoffDelayMs, idleDelayMs shl shift)
    }
}

/**
 * Returns the remaining milliseconds before an idle or unreachable [MediaControlService]
 * should stop itself, or `null` if playback is currently active.
 */
internal fun remainingIdleStopDelayMs(
    status: RemoteStatus?,
    nowElapsedMs: Long,
    lastActiveElapsedMs: Long,
    idleStopMs: Long,
    consecutivePollFailures: Int = 0,
    unreachableStopMs: Long = idleStopMs,
): Long? {
    if (status?.active == true) return null
    val thresholdMs = if (consecutivePollFailures >= 3) {
        minOf(idleStopMs, unreachableStopMs)
    } else {
        idleStopMs
    }
    val elapsedMs = (nowElapsedMs - lastActiveElapsedMs).coerceAtLeast(0L)
    return (thresholdMs - elapsedMs).coerceAtLeast(0L)
}

/** Coalesces refresh requests and carries their obligation across invalidated polls. */
internal class AuthoritativeRefreshState {
    private var pending = false
    private var requiredByPoll = false

    fun request() {
        pending = true
    }

    fun beginPoll() {
        requiredByPoll = requiredByPoll || pending
        pending = false
    }

    fun pollCompleted() {
        requiredByPoll = false
    }

    fun pollInvalidated() {
        pending = pending || requiredByPoll
        requiredByPoll = false
    }

    fun authoritativeStatusReceived() {
        pending = false
        requiredByPoll = false
    }

    fun followUpRequired(): Boolean = pending

    fun reset() {
        pending = false
        requiredByPoll = false
    }
}

internal fun retainedStatusRecheckDelayMs(remainingMs: Long, maximumDelayMs: Long): Long =
    minOf(maximumDelayMs.coerceAtLeast(1L), remainingMs.coerceAtLeast(0L) + 1L)
