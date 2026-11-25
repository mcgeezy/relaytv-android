package pro.relaytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareActivityTest {

    @Test
    fun extractUrlAcceptsHttpHttpsRtspAndRtsps() {
        assertEquals(
            "http://192.168.1.10:8080/video.mp4",
            ShareActivity.extractUrl("http://192.168.1.10:8080/video.mp4"),
        )
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            ShareActivity.extractUrl("https://youtu.be/dQw4w9WgXcQ"),
        )
        assertEquals(
            "rtsp://192.168.1.50:554/stream1",
            ShareActivity.extractUrl("rtsp://192.168.1.50:554/stream1"),
        )
        assertEquals(
            "rtsps://nvr.lan:7441/AbCdEf123?enableSrtp",
            ShareActivity.extractUrl("rtsps://nvr.lan:7441/AbCdEf123?enableSrtp"),
        )
        assertEquals(
            "rtsp://[fd00::50]:8554/front_door",
            ShareActivity.extractUrl("rtsp://[fd00::50]:8554/front_door"),
        )
    }

    @Test
    fun extractUrlNormalizesUppercaseSchemeWhilePreservingCredentialsAndPathCase() {
        assertEquals(
            "rtsp://admin:SecretPass@192.168.1.50:554/Streaming/Channels/101",
            ShareActivity.extractUrl("RTSP://admin:SecretPass@192.168.1.50:554/Streaming/Channels/101"),
        )
        assertEquals(
            "rtsps://10.0.0.20:7441/CameraFeed",
            ShareActivity.extractUrl("RTSPS://10.0.0.20:7441/CameraFeed"),
        )
        assertEquals(
            "https://example.com/LiveStream",
            ShareActivity.extractUrl("HTTPS://example.com/LiveStream"),
        )
    }

    @Test
    fun extractUrlExtractsEmbeddedLinksAndStripsTrailingPunctuation() {
        assertEquals(
            "rtsp://192.168.1.50:554/front_door",
            ShareActivity.extractUrl("Front door feed (rtsp://192.168.1.50:554/front_door), live now!"),
        )
        assertEquals(
            "rtsps://nvr.local:7441/garage",
            ShareActivity.extractUrl("Check <RTSPS://nvr.local:7441/garage>;"),
        )
        assertEquals(
            "rtsp://go2rtc.lan:8554/driveway",
            ShareActivity.extractUrl("Camera: \"rtsp://go2rtc.lan:8554/driveway\""),
        )
        assertEquals(
            "rtsps://cam.lan:7441/backyard",
            ShareActivity.extractUrl("[rtsps://cam.lan:7441/backyard]'"),
        )
        assertEquals(
            "rtsp://cam.local/live",
            ShareActivity.extractUrl("(rtsp://cam.local/live)"),
        )
        assertEquals(
            "rtsp://cam.local/live",
            ShareActivity.extractUrl("<rtsp://cam.local/live>"),
        )
    }

    @Test
    fun extractUrlRejectsBareSchemesMissingHostsAndUnsupportedSchemes() {
        assertNull(ShareActivity.extractUrl(null))
        assertNull(ShareActivity.extractUrl(""))
        assertNull(ShareActivity.extractUrl("   "))

        // Bare schemes (including bare schemes followed only by trimmed punctuation)
        assertNull(ShareActivity.extractUrl("http://"))
        assertNull(ShareActivity.extractUrl("https://"))
        assertNull(ShareActivity.extractUrl("rtsp://"))
        assertNull(ShareActivity.extractUrl("rtsps://"))
        assertNull(ShareActivity.extractUrl("(https://)"))
        assertNull(ShareActivity.extractUrl("\"rtsps://\""))
        assertNull(ShareActivity.extractUrl("rtsp://,"))
        assertNull(ShareActivity.extractUrl("rtsp:///path-without-host"))
        assertNull(ShareActivity.extractUrl("rtsp://user:pass@"))
        assertNull(ShareActivity.extractUrl("rtsp://user:pass@/stream1"))
        assertNull(ShareActivity.extractUrl("rtsp://:554/stream1"))

        // Unsupported schemes
        assertNull(ShareActivity.extractUrl("file:///sdcard/Movies/clip.mp4"))
        assertNull(ShareActivity.extractUrl("rtmp://192.168.1.50/live/stream"))
        assertNull(ShareActivity.extractUrl("rtmps://live.example.com/app/key"))
        assertNull(ShareActivity.extractUrl("content://media/external/video/media/42"))
        assertNull(ShareActivity.extractUrl("ftp://192.168.1.50/pub/video.mp4"))
        assertNull(ShareActivity.extractUrl("myrtsp://192.168.1.50:554/stream1"))
        assertNull(ShareActivity.extractUrl("x-rtsp://cam.local/live"))
        assertNull(ShareActivity.extractUrl("foo+rtsp://cam.local/live"))
        assertNull(ShareActivity.extractUrl("v2rtsp://cam.local/live"))
    }

    @Test
    fun extractSharedHttpUrlExtractsRtspAndRtspsFromActionViewDataUri() {
        assertEquals(
            "rtsp://192.168.1.50:554/front_door",
            ShareActivity.extractSharedHttpUrl(
                extraText = null,
                dataUri = "rtsp://192.168.1.50:554/front_door",
            ),
        )
        assertEquals(
            "rtsps://nvr.lan:7441/doorbell",
            ShareActivity.extractSharedHttpUrl(
                extraText = null,
                dataUri = "RTSPS://nvr.lan:7441/doorbell",
            ),
        )
        assertEquals(
            "rtsp://go2rtc.lan:8554/porch",
            ShareActivity.extractSharedHttpUrl(
                extraText = "Porch camera",
                clipCandidates = listOf("not a link"),
                dataUri = "rtsp://go2rtc.lan:8554/porch",
            ),
        )
    }

    @Test
    fun isLocalStreamSchemeDoesNotMisclassifyRtspOrHttpAsLocalMedia() {
        assertTrue(ShareActivity.isLocalStreamScheme("content"))
        assertTrue(ShareActivity.isLocalStreamScheme("file"))
        assertFalse(ShareActivity.isLocalStreamScheme("rtsp"))
        assertFalse(ShareActivity.isLocalStreamScheme("rtsps"))
        assertFalse(ShareActivity.isLocalStreamScheme("RTSP"))
        assertFalse(ShareActivity.isLocalStreamScheme("http"))
        assertFalse(ShareActivity.isLocalStreamScheme("https"))
        assertFalse(ShareActivity.isLocalStreamScheme(null))
    }

    @Test
    fun shareWorkerSanitizeUrlForDisplayRedactsRtspCredentials() {
        assertEquals(
            "rtsp://192.168.1.50:554/front_door",
            ShareWorker.sanitizeUrlForDisplay("rtsp://admin:secret@192.168.1.50:554/front_door"),
        )
        assertEquals(
            "rtsps://192.168.1.50:7441/live?channel=1",
            ShareWorker.sanitizeUrlForDisplay(
                "RTSPS://admin:p@ss@192.168.1.50:7441/live?user=admin&password=secret&channel=1"
            ),
        )
        assertEquals(
            "rtsp://go2rtc.lan:8554/front_door",
            ShareWorker.sanitizeUrlForDisplay("rtsp://go2rtc.lan:8554/front_door?usr=cam&pwd=1234"),
        )
        assertEquals(
            "rtsp://192.168.1.50:554/live?bad%ZZkey=1",
            ShareWorker.sanitizeUrlForDisplay(
                "rtsp://admin:secret@192.168.1.50:554/live?bad%ZZkey=1&password=secret"
            ),
        )
    }
}
