package pro.relaytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class NetTest {

    @Test
    fun postJsonAddsBearerToken() {
        val request = Net.postJson(
            url = "https://relay.example/pause",
            json = "{}",
            apiToken = "  secret-token  ",
        )

        assertEquals("Bearer secret-token", request.header("Authorization"))
        assertEquals("https://relay.example/pause", request.url.toString())
    }

    @Test
    fun blankTokenLeavesAuthorizationHeaderUnset() {
        val request = Net.postJson(
            url = "https://relay.example/pause",
            json = "{}",
            apiToken = "   ",
        )

        assertNull(request.header("Authorization"))
    }

    @Test
    fun multipartUploadAddsBearerToken() {
        val file = File.createTempFile("relaytv-test-", ".mp3")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val request = Net.postMultipartFile(
                url = "https://relay.example/ingest/media/play",
                file = file,
                mimeType = "audio/mpeg",
                apiToken = "upload-token",
            )

            assertEquals("Bearer upload-token", request.header("Authorization"))
        } finally {
            file.delete()
        }
    }
}
