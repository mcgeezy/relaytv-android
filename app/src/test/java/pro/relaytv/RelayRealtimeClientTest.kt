package pro.relaytv

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RelayRealtimeClientTest {
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<RelayRealtimeClient>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.shutdown() }
    }

    @Test
    fun convertsHttpSchemesAndPreservesReverseProxyPrefix() {
        assertEquals(
            "ws://relay.local:8787/relaytv/ui/ws",
            RelayRealtimeClient.websocketUrl("http://relay.local:8787/relaytv", "/ui/ws"),
        )
        assertEquals(
            "wss://tv.example/relaytv/ui/ws",
            RelayRealtimeClient.websocketUrl("https://tv.example/relaytv", "ui/ws"),
        )
    }

    @Test
    fun parsesOnlyVersionedRealtimeEnvelopes() {
        val envelope = RelayRealtimeClient.parseEnvelope(
            """{"version":1,"event":"playback","sequence":42,"data":{"playing":true}}"""
        )

        assertEquals("playback", envelope?.event)
        assertEquals(42L, envelope?.sequence)
        assertTrue(envelope?.data?.optBoolean("playing") == true)
        assertEquals(null, RelayRealtimeClient.parseEnvelope("""{"version":2,"event":"status"}"""))
    }

    @Test
    fun compactPlaybackPatchPreservesAuthoritativeMetadata() {
        val status = RemoteStatus(
            playing = false,
            title = "Movie",
            thumbnail = "/thumb.jpg",
            durationSec = 120.0,
        )

        val merged = status.mergePlayback(
            JSONObject("""{"playing":true,"paused":false,"position":12.5}""")
        )

        assertTrue(merged.playing)
        assertFalse(merged.paused)
        assertEquals(12.5, merged.positionSec)
        assertEquals("Movie", merged.title)
        assertEquals("/thumb.jpg", merged.thumbnail)
        assertEquals(120.0, merged.durationSec)
    }

    @Test
    fun websocketNegotiatesProtocolAndDeliversStatusWithoutTokenInUrl() {
        val server = MockWebServer().also {
            it.start()
            servers += it
        }
        server.enqueue(
            MockResponse().setBody(
                """{
                    "protocol_version":1,
                    "websocket":{"enabled":true,"ui":"/ui/ws","subprotocol":"relaytv.realtime.v1"},
                    "sse":{"enabled":true,"ui":"/ui/events"}
                }""".trimIndent()
            )
        )
        server.enqueue(
            MockResponse()
                .setHeader("Sec-WebSocket-Protocol", RelayRealtimeClient.REALTIME_SUBPROTOCOL)
                .withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            """{"version":1,"event":"hello","sequence":0,"data":{"protocol_version":1}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"status","sequence":1,"data":{"playing":true}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"queue","sequence":3,"data":{"queue_length":2}}"""
                        )
                    }
                })
        )
        val connected = CountDownLatch(1)
        val status = CountDownLatch(1)
        val refreshes = CountDownLatch(2)
        val client = RelayRealtimeClient(listener = object : RelayRealtimeClient.Listener {
            override fun onTransportChanged(
                owner: Long,
                identity: String,
                transport: RelayRealtimeClient.Transport?,
            ) {
                if (identity == "server-a" && transport == RelayRealtimeClient.Transport.WEBSOCKET) {
                    connected.countDown()
                }
            }

            override fun onEvent(owner: Long, identity: String, event: String, data: JSONObject) {
                if (identity == "server-a" && event == "status" && data.optBoolean("playing")) {
                    status.countDown()
                }
            }

            override fun onAuthoritativeRefreshRequired(owner: Long, identity: String) {
                if (identity == "server-a") refreshes.countDown()
            }
        }).also { clients += it }

        client.start(
            RelayRealtimeClient.Config(
                identity = "server-a",
                baseUrl = server.url("/").toString().trimEnd('/'),
                apiToken = "secret-token",
            )
        )

        assertTrue(connected.await(3, TimeUnit.SECONDS))
        assertTrue(status.await(3, TimeUnit.SECONDS))
        assertTrue(refreshes.await(3, TimeUnit.SECONDS))
        val capabilityRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
        val websocketRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/realtime/capabilities", capabilityRequest.path)
        assertEquals("/ui/ws", websocketRequest.path)
        assertEquals("Bearer secret-token", websocketRequest.getHeader("Authorization"))
        assertFalse(websocketRequest.requestUrl.toString().contains("secret-token"))
    }

    @Test
    fun legacyCapability404FallsBackToSse() {
        val server = MockWebServer().also {
            it.start()
            servers += it
        }
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: status\ndata: {\"playing\":true}\n\n")
        )
        val connected = CountDownLatch(1)
        val status = CountDownLatch(1)
        val client = RelayRealtimeClient(listener = object : RelayRealtimeClient.Listener {
            override fun onTransportChanged(
                owner: Long,
                identity: String,
                transport: RelayRealtimeClient.Transport?,
            ) {
                if (identity == "legacy" && transport == RelayRealtimeClient.Transport.SSE) {
                    connected.countDown()
                }
            }

            override fun onEvent(owner: Long, identity: String, event: String, data: JSONObject) {
                if (identity == "legacy" && event == "status" && data.optBoolean("playing")) {
                    status.countDown()
                }
            }

            override fun onAuthoritativeRefreshRequired(owner: Long, identity: String) = Unit
        }).also { clients += it }

        client.start(
            RelayRealtimeClient.Config(
                identity = "legacy",
                baseUrl = server.url("/").toString().trimEnd('/'),
                apiToken = "",
            )
        )

        assertTrue(connected.await(3, TimeUnit.SECONDS))
        assertTrue(status.await(3, TimeUnit.SECONDS))
        assertEquals("/realtime/capabilities", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        assertEquals("/ui/events", server.takeRequest(3, TimeUnit.SECONDS)?.path)
    }
}
