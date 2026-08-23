package pro.relaytv

import org.json.JSONObject
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
}
