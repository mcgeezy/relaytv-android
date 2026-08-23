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
