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
