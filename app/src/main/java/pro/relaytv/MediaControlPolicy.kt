package pro.relaytv

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

/** Coalesces refresh requests while ensuring a request arriving during a poll is not lost. */
internal class AuthoritativeRefreshState {
    private var pending = false

    fun request() {
        pending = true
    }

    fun beginPoll() {
        pending = false
    }

    fun followUpRequired(): Boolean = pending

    fun reset() {
        pending = false
    }
}

internal fun retainedStatusRecheckDelayMs(remainingMs: Long, maximumDelayMs: Long): Long =
    minOf(maximumDelayMs.coerceAtLeast(1L), remainingMs.coerceAtLeast(0L) + 1L)
