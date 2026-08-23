package pro.relaytv

import org.json.JSONObject

/** Owns the cached media snapshot and orders HTTP polls against realtime state. */
internal class MediaStatusState {
    private var revision = 0L

    var status: RemoteStatus? = null
        private set

    var statusAtMs: Long = 0L
        private set

    fun beginPoll(): Long = revision

    fun isCurrent(owner: Long): Boolean = owner == revision

    fun acceptPoll(owner: Long, next: RemoteStatus, nowMs: Long): Boolean {
        if (!isCurrent(owner)) return false
        record(next, nowMs)
        return true
    }

    fun acceptRealtime(next: RemoteStatus, nowMs: Long): RemoteStatus {
        revision += 1
        record(next, nowMs)
        return next
    }

    fun mergeRealtimePlayback(patch: JSONObject, nowMs: Long): RemoteStatus? {
        val current = status ?: return null
        revision += 1
        return current.mergePlayback(patch).also { record(it, nowMs) }
    }

    fun retained(nowMs: Long, graceMs: Long): RemoteStatus? =
        status?.takeIf { remainingRetentionMs(nowMs, graceMs) != null }

    fun remainingRetentionMs(nowMs: Long, graceMs: Long): Long? {
        if (status == null || statusAtMs <= 0L) return null
        return (graceMs - (nowMs - statusAtMs)).takeIf { it >= 0L }
    }

    fun reset() {
        revision += 1
        status = null
        statusAtMs = 0L
    }

    private fun record(next: RemoteStatus, nowMs: Long) {
        status = next
        statusAtMs = nowMs
    }
}
