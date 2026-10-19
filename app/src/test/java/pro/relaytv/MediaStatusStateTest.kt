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

    @Test
    fun liveDvrStatusControlsSeekabilityAndBounds() {
        val seekableLive = RemoteStatus.parse(
            """
            {
              "playing": true,
              "paused": false,
              "position": 95.0,
              "duration": null,
              "dvr": {
                "supported": true,
                "seekable": true,
                "buffer_start": 30.0,
                "buffer_end": 120.0,
                "live_edge": 120.0
              }
            }
            """.trimIndent()
        )
        assertTrue(seekableLive.isSeekable)
        assertEquals(120.0, seekableLive.effectiveDurationSec ?: 0.0, 0.001)
        assertEquals(30.0, seekableLive.clampSeekSec(10.0), 0.001)
        assertEquals(85.0, seekableLive.clampSeekSec(85.0), 0.001)
        assertEquals(120.0, seekableLive.clampSeekSec(150.0), 0.001)

        val unseekableLiveWithNominalDuration = RemoteStatus.parse(
            """
            {
              "playing": true,
              "paused": false,
              "position": 5.0,
              "duration": 3600.0,
              "dvr": {
                "supported": true,
                "seekable": false,
                "buffer_start": null,
                "live_edge": null
              }
            }
            """.trimIndent()
        )
        assertFalse(unseekableLiveWithNominalDuration.isSeekable)
        assertNull(unseekableLiveWithNominalDuration.effectiveDurationSec)
    }

    @Test
    fun mergePlaybackClearsStaleMetricsAndMetadataWhenStopped() {
        val active = RemoteStatus(
            playing = true,
            paused = false,
            positionSec = 42.0,
            durationSec = 180.0,
            title = "Live Channel",
            thumbnail = "/thumbs/ch.jpg",
            volumePercent = 70,
            dvr = RemoteDvrStatus(supported = true, seekable = true, bufferStartSec = 0.0, liveEdgeSec = 60.0),
        )

        val transientNullWhilePlaying = active.mergePlayback(
            JSONObject("""{"playing":true,"paused":false,"position":null,"duration":null}""")
        )
        assertEquals(42.0, transientNullWhilePlaying.positionSec ?: 0.0, 0.001)
        assertEquals(180.0, transientNullWhilePlaying.durationSec ?: 0.0, 0.001)

        val stopped = active.mergePlayback(
            JSONObject("""{"playing":false,"paused":false,"has_now_playing":false,"position":null,"duration":null,"dvr":null}""")
        )
        assertFalse(stopped.active)
        assertNull(stopped.positionSec)
        assertNull(stopped.durationSec)
        assertNull(stopped.title)
        assertNull(stopped.thumbnail)
        assertNull(stopped.dvr)
        assertEquals(70, stopped.volumePercent)
    }

    @Test
    fun shareResponseMessagesCoverPlayNowPlaylistJobsAndErrors() {
        assertEquals(
            "Playing now",
            ShareWorker.parseShareResponseMessage(200, true, """{"ok":true,"action":"played"}"""),
        )
        assertEquals(
            "Enqueued",
            ShareWorker.parseShareResponseMessage(200, true, """{"status":"queued"}"""),
        )
        assertEquals(
            "Reading playlist…",
            ShareWorker.parseShareResponseMessage(
                202,
                true,
                """{"job_id":"abc","status":"loading","effective_action":"play_now","detail":"Reading playlist…"}""",
            ),
        )
        assertEquals(
            "API token required",
            ShareWorker.parseShareResponseMessage(401, false, """{"detail":"Unauthorized"}"""),
        )
        assertEquals(
            "Send failed: Dynamic YouTube mixes cannot be imported as a playlist",
            ShareWorker.parseShareResponseMessage(
                400,
                false,
                """{"detail":"Dynamic YouTube mixes cannot be imported as a playlist"}""",
            ),
        )
    }

    @Test
    fun sameServerUrlMatchesOnlyActiveServerOriginAndPrefix() {
        assertTrue(HostStore.isSameServerUrl("http://10.0.55.2:8787", "http://10.0.55.2:8787/ui"))
        assertTrue(HostStore.isSameServerUrl("http://10.0.55.2:8787/", "http://10.0.55.2:8787/ui?share=1"))
        assertFalse(
            HostStore.isSameServerUrl(
                "http://10.0.55.2:8787",
                "https://app.plex.tv/auth#?clientID=abc&code=123",
            )
        )
        assertFalse(
            HostStore.isSameServerUrl(
                "http://10.0.55.2:8787",
                "https://github.com/markmcgee/relaytv",
            )
        )
        assertTrue(
            HostStore.isSameServerUrl(
                "http://example.com/relaytv",
                "http://example.com/relaytv/ui",
            )
        )
        assertFalse(
            HostStore.isSameServerUrl(
                "http://example.com/relaytv",
                "http://example.com/other",
            )
        )
    }

    @Test
    fun shareExceptionPolicySeparatesRemoteTimeoutsFromUploadTimeoutsAndCapsRetries() {
        assertEquals(
            ShareWorker.Companion.ExceptionAction.SUCCESS_PROCESSING,
            ShareWorker.resolveExceptionAction(
                isLocalUpload = false,
                isTimeout = true,
                runAttemptCount = 0,
            ),
        )
        assertEquals(
            ShareWorker.Companion.ExceptionAction.RETRY,
            ShareWorker.resolveExceptionAction(
                isLocalUpload = true,
                isTimeout = true,
                runAttemptCount = 0,
            ),
        )
        assertEquals(
            ShareWorker.Companion.ExceptionAction.FAILURE,
            ShareWorker.resolveExceptionAction(
                isLocalUpload = true,
                isTimeout = true,
                runAttemptCount = 3,
            ),
        )
        assertEquals(
            ShareWorker.Companion.ExceptionAction.FAILURE,
            ShareWorker.resolveExceptionAction(
                isLocalUpload = false,
                isTimeout = false,
                runAttemptCount = 3,
            ),
        )
        assertTrue(ShareWorker.isTimeoutError(java.net.SocketTimeoutException()))
    }
}
