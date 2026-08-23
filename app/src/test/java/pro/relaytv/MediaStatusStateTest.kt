package pro.relaytv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStatusStateTest {
    @Test
    fun serverSwitchClearsCachedStatusAndRejectsOldPoll() {
        val state = MediaStatusState()
        state.acceptRealtime(
            RemoteStatus(playing = true, title = "Server A movie", thumbnail = "/a.jpg"),
            nowMs = 100,
        )
        val oldPoll = state.beginPoll()

        state.reset()

        assertNull(state.status)
        assertNull(state.retained(nowMs = 110, graceMs = 30_000))
        assertNull(
            state.mergeRealtimePlayback(
                JSONObject("""{"playing":false,"paused":true}"""),
                nowMs = 120,
            )
        )
        assertFalse(
            state.acceptPoll(
                oldPoll,
                RemoteStatus(playing = true, title = "Server A movie"),
                nowMs = 130,
            )
        )
    }

    @Test
    fun realtimeStateRejectsPollThatStartedBeforeIt() {
        val state = MediaStatusState()
        val bootstrap = state.beginPoll()
        assertTrue(
            state.acceptPoll(
                bootstrap,
                RemoteStatus(playing = true, paused = false, positionSec = 10.0),
                nowMs = 100,
            )
        )
        val stalePoll = state.beginPoll()

        state.acceptRealtime(
            RemoteStatus(playing = true, paused = true, positionSec = 25.0),
            nowMs = 200,
        )

        assertFalse(
            state.acceptPoll(
                stalePoll,
                RemoteStatus(playing = true, paused = false, positionSec = 10.0),
                nowMs = 300,
            )
        )
        assertTrue(state.status?.paused == true)
        assertTrue(state.status?.positionSec == 25.0)
    }

    @Test
    fun unmergeablePlaybackPreservesAuthoritativeBootstrapPoll() {
        val state = MediaStatusState()
        val bootstrap = state.beginPoll()

        assertNull(
            state.mergeRealtimePlayback(
                JSONObject("""{"playing":true,"position":12.0}"""),
                nowMs = 100,
            )
        )
        assertTrue(state.isCurrent(bootstrap))
        assertTrue(
            state.acceptPoll(
                bootstrap,
                RemoteStatus(playing = true, title = "Bootstrapped"),
                nowMs = 200,
            )
        )
    }

    @Test
    fun healthyPushNudgesDoNotSchedulePolls() {
        assertFalse(
            shouldScheduleMediaPoll(
                identityChanged = false,
                pushHealthy = true,
                pollInFlight = false,
                hasAuthoritativeStatus = true,
            )
        )
        assertTrue(
            shouldScheduleMediaPoll(
                identityChanged = true,
                pushHealthy = true,
                pollInFlight = false,
                hasAuthoritativeStatus = false,
            )
        )
        assertTrue(
            shouldScheduleMediaPoll(
                identityChanged = false,
                pushHealthy = false,
                pollInFlight = false,
                hasAuthoritativeStatus = true,
            )
        )
    }

    @Test
    fun callbackMustMatchConnectionAndSelectedHost() {
        assertTrue(
            realtimeCallbackMatchesActiveHost(
                callbackOwner = 2,
                activeOwner = 2,
                callbackIdentity = "server-b",
                connectionIdentity = "server-b",
                selectedIdentity = "server-b",
            )
        )
        assertFalse(
            realtimeCallbackMatchesActiveHost(
                callbackOwner = 2,
                activeOwner = 2,
                callbackIdentity = "server-a",
                connectionIdentity = "server-a",
                selectedIdentity = "server-b",
            )
        )
    }

    @Test
    fun refreshRequestedDuringPollRequiresFollowUp() {
        val state = AuthoritativeRefreshState()
        state.request()

        state.beginPoll()
        assertFalse(state.followUpRequired())

        state.request()
        state.pollCompleted()
        assertTrue(state.followUpRequired())

        state.beginPoll()
        state.pollCompleted()
        assertFalse(state.followUpRequired())
    }

    @Test
    fun invalidatedRequiredPollRestoresRefreshObligation() {
        val state = AuthoritativeRefreshState()
        state.request()
        state.beginPoll()

        state.pollInvalidated()

        assertTrue(state.followUpRequired())
        state.beginPoll()
        state.pollCompleted()
        assertFalse(state.followUpRequired())
    }

    @Test
    fun authoritativePushSatisfiesInvalidatedPollObligation() {
        val state = AuthoritativeRefreshState()
        state.request()
        state.beginPoll()

        state.authoritativeStatusReceived()
        state.pollInvalidated()

        assertFalse(state.followUpRequired())
    }

    @Test
    fun retainedStatusIsRecheckedUntilItExpires() {
        val state = MediaStatusState()
        state.acceptRealtime(RemoteStatus(playing = true), nowMs = 100)

        assertEquals(29_999L, state.remainingRetentionMs(nowMs = 101, graceMs = 30_000))
        assertEquals(3_000L, retainedStatusRecheckDelayMs(29_999, 3_000))
        assertEquals(1L, state.remainingRetentionMs(nowMs = 30_099, graceMs = 30_000))
        assertEquals(2L, retainedStatusRecheckDelayMs(1, 3_000))
        assertNull(state.retained(nowMs = 30_101, graceMs = 30_000))
    }
}
