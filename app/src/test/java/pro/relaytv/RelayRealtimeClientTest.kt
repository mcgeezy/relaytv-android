package pro.relaytv

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

        assertTrue(connected.await(10, TimeUnit.SECONDS))
        assertTrue(status.await(10, TimeUnit.SECONDS))
        assertEquals("/realtime/capabilities", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        assertEquals("/ui/events", server.takeRequest(3, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun legacySsePeriodicallyRediscoversAndUpgradesToWebsocket() {
        val server = MockWebServer().also {
            it.start()
            servers += it
        }
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: ping\ndata: {}\n\n")
                .throttleBody(1, 1, TimeUnit.SECONDS)
        )
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
                    }
                })
        )
        val usedLegacySse = CountDownLatch(1)
        val upgraded = CountDownLatch(1)
        val client = RelayRealtimeClient(
            capabilityRefreshMs = 100,
            listener = object : RelayRealtimeClient.Listener {
                override fun onTransportChanged(
                    owner: Long,
                    identity: String,
                    transport: RelayRealtimeClient.Transport?,
                ) {
                    if (transport == RelayRealtimeClient.Transport.SSE) usedLegacySse.countDown()
                    if (transport == RelayRealtimeClient.Transport.WEBSOCKET) upgraded.countDown()
                }

                override fun onEvent(
                    owner: Long,
                    identity: String,
                    event: String,
                    data: JSONObject,
                ) = Unit

                override fun onAuthoritativeRefreshRequired(owner: Long, identity: String) = Unit
            },
        ).also { clients += it }

        client.start(
            RelayRealtimeClient.Config(
                identity = "upgrade",
                baseUrl = server.url("/").toString().trimEnd('/'),
                apiToken = "",
            )
        )

        assertTrue(usedLegacySse.await(3, TimeUnit.SECONDS))
        assertTrue(upgraded.await(3, TimeUnit.SECONDS))
        assertEquals("/realtime/capabilities", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        assertEquals("/ui/events", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        assertEquals("/realtime/capabilities", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        assertEquals("/ui/ws", server.takeRequest(3, TimeUnit.SECONDS)?.path)
    }

    @Test
    fun synchronousWebsocketHelloIsOwnedBeforeFactoryReturns() {
        val server = capabilityServer()
        val connected = CountDownLatch(1)
        val webSocketFactory = WebSocket.Factory { request, listener ->
            FakeWebSocket(request).also { socket ->
                listener.onMessage(
                    socket,
                    """{"version":1,"event":"hello","sequence":0,"data":{"protocol_version":1}}""",
                )
            }
        }
        val client = RelayRealtimeClient(
            webSocketFactory = webSocketFactory,
            listener = object : EmptyRealtimeListener() {
                override fun onTransportChanged(
                    owner: Long,
                    identity: String,
                    transport: RelayRealtimeClient.Transport?,
                ) {
                    if (transport == RelayRealtimeClient.Transport.WEBSOCKET) connected.countDown()
                }
            },
        ).also { clients += it }

        client.start(configFor(server, "synchronous-hello"))

        assertTrue(connected.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun synchronousTerminalCallbacksFallBackBeforeFactoriesReturn() {
        val server = capabilityServer()
        val connected = CountDownLatch(1)
        val webSocketFactory = WebSocket.Factory { request, listener ->
            FakeWebSocket(request).also { socket ->
                listener.onFailure(socket, IOException("closed during connect"), null)
            }
        }
        val eventSourceFactory = EventSource.Factory { request, listener ->
            FakeEventSource(request).also { source ->
                listener.onOpen(source, successfulResponse(request))
            }
        }
        val client = RelayRealtimeClient(
            webSocketFactory = webSocketFactory,
            eventSourceFactory = eventSourceFactory,
            listener = object : EmptyRealtimeListener() {
                override fun onTransportChanged(
                    owner: Long,
                    identity: String,
                    transport: RelayRealtimeClient.Transport?,
                ) {
                    if (transport == RelayRealtimeClient.Transport.SSE) connected.countDown()
                }
            },
        ).also { clients += it }

        client.start(configFor(server, "synchronous-failure"))

        assertTrue(connected.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun staleApplicationSequencesAreNotDelivered() {
        val server = MockWebServer().also {
            it.start()
            servers += it
        }
        server.enqueue(capabilityResponse())
        server.enqueue(
            MockResponse()
                .setHeader("Sec-WebSocket-Protocol", RelayRealtimeClient.REALTIME_SUBPROTOCOL)
                .withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            """{"version":1,"event":"hello","sequence":0,"data":{"protocol_version":1}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"status","sequence":2,"data":{"playing":true}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"playback","sequence":1,"data":{"playing":false}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"queue","sequence":3,"data":{"queue_length":2}}"""
                        )
                    }
                })
        )
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val queueDelivered = CountDownLatch(1)
        val client = RelayRealtimeClient(listener = object : EmptyRealtimeListener() {
            override fun onEvent(
                owner: Long,
                identity: String,
                event: String,
                data: JSONObject,
            ) {
                delivered += event
                if (event == "queue") queueDelivered.countDown()
            }
        }).also { clients += it }

        client.start(configFor(server, "ordered-events"))

        assertTrue(queueDelivered.await(3, TimeUnit.SECONDS))
        assertFalse(delivered.contains("playback"))
        assertTrue(delivered.containsAll(listOf("hello", "status", "queue")))
    }

    @Test
    fun pingsAheadOfAppliedStateRequestOneRefreshPerCheckpoint() {
        val server = MockWebServer().also {
            it.start()
            servers += it
        }
        server.enqueue(capabilityResponse())
        server.enqueue(
            MockResponse()
                .setHeader("Sec-WebSocket-Protocol", RelayRealtimeClient.REALTIME_SUBPROTOCOL)
                .withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            """{"version":1,"event":"hello","sequence":0,"data":{"protocol_version":1}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"status","sequence":41,"data":{"playing":true}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"ping","sequence":42,"data":{"checkpoint":42}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"ping","sequence":42,"data":{"checkpoint":42}}"""
                        )
                        webSocket.send(
                            """{"version":1,"event":"ping","sequence":43,"data":{"checkpoint":43}}"""
                        )
                    }
                })
        )
        val refreshCount = AtomicInteger()
        val finalPing = CountDownLatch(1)
        val client = RelayRealtimeClient(listener = object : EmptyRealtimeListener() {
            override fun onEvent(
                owner: Long,
                identity: String,
                event: String,
                data: JSONObject,
            ) {
                if (event == "ping" && data.optInt("checkpoint") == 43) finalPing.countDown()
            }

            override fun onAuthoritativeRefreshRequired(owner: Long, identity: String) {
                refreshCount.incrementAndGet()
            }
        }).also { clients += it }

        client.start(configFor(server, "ping-checkpoints"))

        assertTrue(finalPing.await(3, TimeUnit.SECONDS))
        assertEquals(3, refreshCount.get())
    }

    private fun capabilityServer(): MockWebServer = MockWebServer().also {
        it.start()
        it.enqueue(capabilityResponse())
        servers += it
    }

    private fun capabilityResponse(): MockResponse = MockResponse().setBody(
        """{
            "protocol_version":1,
            "websocket":{"enabled":true,"ui":"/ui/ws","subprotocol":"relaytv.realtime.v1"},
            "sse":{"enabled":true,"ui":"/ui/events"}
        }""".trimIndent()
    )

    private fun configFor(server: MockWebServer, identity: String) = RelayRealtimeClient.Config(
        identity = identity,
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiToken = "",
    )

    private fun successfulResponse(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()

    private open class EmptyRealtimeListener : RelayRealtimeClient.Listener {
        override fun onTransportChanged(
            owner: Long,
            identity: String,
            transport: RelayRealtimeClient.Transport?,
        ) = Unit

        override fun onEvent(
            owner: Long,
            identity: String,
            event: String,
            data: JSONObject,
        ) = Unit

        override fun onAuthoritativeRefreshRequired(owner: Long, identity: String) = Unit
    }

    private class FakeWebSocket(private val originalRequest: Request) : WebSocket {
        var cancelled = false

        override fun request(): Request = originalRequest
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = !cancelled
        override fun send(bytes: ByteString): Boolean = !cancelled
        override fun close(code: Int, reason: String?): Boolean = !cancelled
        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeEventSource(private val originalRequest: Request) : EventSource {
        var cancelled = false

        override fun request(): Request = originalRequest
        override fun cancel() {
            cancelled = true
        }
    }
}
