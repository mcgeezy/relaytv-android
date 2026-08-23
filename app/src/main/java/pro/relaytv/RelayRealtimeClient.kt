package pro.relaytv

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/** Selects WebSocket, SSE, then polling for the native media-control consumer. */
class RelayRealtimeClient(
    private val requestClient: OkHttpClient = Net.client,
    private val streamingClient: OkHttpClient = Net.streamingClient,
    private val webSocketFactory: WebSocket.Factory = streamingClient,
    private val eventSourceFactory: EventSource.Factory = EventSources.createFactory(streamingClient),
    private val capabilityRefreshMs: Long = CAPABILITY_REFRESH_MS,
    private val listener: Listener,
) {
    enum class Transport { WEBSOCKET, SSE }

    data class Config(
        val identity: String,
        val baseUrl: String,
        val apiToken: String,
    )

    data class Envelope(
        val version: Int,
        val event: String,
        val sequence: Long,
        val data: JSONObject,
    )

    interface Listener {
        fun onTransportChanged(owner: Long, identity: String, transport: Transport?)
        fun onEvent(owner: Long, identity: String, event: String, data: JSONObject)
        fun onAuthoritativeRefreshRequired(owner: Long, identity: String)
    }

    private data class Capabilities(
        val websocketEnabled: Boolean,
        val websocketPath: String,
        val websocketProtocol: String,
        val sseEnabled: Boolean,
        val ssePath: String,
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "relaytv-realtime-retry").apply { isDaemon = true }
    }
    private var generation = 0L
    private var config: Config? = null
    private var capabilities: Capabilities? = null
    private var capabilityCall: Call? = null
    private var webSocket: WebSocket? = null
    private var webSocketAttempt: Any? = null
    private var eventSource: EventSource? = null
    private var eventSourceAttempt: Any? = null
    private var retryFuture: ScheduledFuture<*>? = null
    private var capabilityRefreshFuture: ScheduledFuture<*>? = null
    private var activeTransport: Transport? = null
    private var failureCount = 0
    private var lastSequence = 0L
    private var highestRefreshRequestedSequence = 0L
    private var closed = false

    @Synchronized
    fun start(nextConfig: Config): Long {
        if (closed) return generation
        retireLocked()
        generation += 1
        config = nextConfig
        capabilities = null
        failureCount = 0
        lastSequence = 0
        highestRefreshRequestedSequence = 0
        discover(generation)
        return generation
    }

    @Synchronized
    fun restart(): Long? = config?.let { start(it) }

    @Synchronized
    fun stop() {
        if (closed && config == null) return
        generation += 1
        retireLocked()
        config = null
        capabilities = null
        lastSequence = 0
        highestRefreshRequestedSequence = 0
    }

    @Synchronized
    fun close() {
        if (closed) return
        stop()
        closed = true
        scheduler.shutdownNow()
    }

    @Synchronized
    fun isPushHealthy(): Boolean = activeTransport != null

    private fun discover(owner: Long) {
        val current = synchronized(this) { config?.takeIf { ownsLocked(owner) } } ?: return
        val request = Net.get(resolveHttpUrl(current.baseUrl, "/realtime/capabilities"), current.apiToken)
        val call = requestClient.newCall(request)
        synchronized(this) {
            if (!ownsLocked(owner)) {
                call.cancel()
                return
            }
            capabilityCall = call
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@RelayRealtimeClient) {
                    if (capabilityCall !== call || !ownsLocked(owner)) return
                    capabilityCall = null
                    setTransportLocked(owner, null)
                    scheduleRetryLocked(owner)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val discovered = response.use { parseCapabilities(it) }
                synchronized(this@RelayRealtimeClient) {
                    if (capabilityCall !== call || !ownsLocked(owner)) return
                    capabilityCall = null
                    if (discovered == null) {
                        setTransportLocked(owner, null)
                        scheduleRetryLocked(owner)
                        return
                    }
                    capabilities = discovered
                }
                selectBestTransport(owner)
            }
        })
    }

    private fun selectBestTransport(owner: Long) {
        val available = synchronized(this) {
            capabilities?.takeIf { ownsLocked(owner) }
        } ?: return
        if (available.websocketEnabled) {
            connectWebSocket(owner, available)
        } else if (available.sseEnabled) {
            connectSse(owner, available)
        } else {
            synchronized(this) {
                if (ownsLocked(owner)) scheduleRetryLocked(owner)
            }
        }
    }

    private fun connectWebSocket(owner: Long, available: Capabilities) {
        val current = synchronized(this) { config?.takeIf { ownsLocked(owner) } } ?: return
        if (available.websocketProtocol != REALTIME_SUBPROTOCOL) {
            connectSseOrRetry(owner, available)
            return
        }
        val request = Net.get(
            websocketUrl(current.baseUrl, available.websocketPath),
            current.apiToken,
        ).newBuilder()
            .header("Sec-WebSocket-Protocol", REALTIME_SUBPROTOCOL)
            .build()
        val attempt = Any()
        val installed = synchronized(this) {
            if (
                !ownsLocked(owner) ||
                webSocket != null ||
                webSocketAttempt != null ||
                eventSource != null ||
                eventSourceAttempt != null
            ) {
                false
            } else {
                webSocketAttempt = attempt
                true
            }
        }
        if (!installed) return
        var receivedHello = false
        val callback = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != REALTIME_SUBPROTOCOL) {
                    failWebSocket(owner, attempt, webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = parseEnvelope(text)
                if (envelope == null) {
                    failWebSocket(owner, attempt, webSocket)
                    return
                }
                synchronized(this@RelayRealtimeClient) {
                    if (!ownsWebSocketAttemptLocked(owner, attempt)) return
                    if (!receivedHello) {
                        if (
                            envelope.event != "hello" ||
                            envelope.data.optInt("protocol_version", -1) != PROTOCOL_VERSION
                        ) {
                            failWebSocketLocked(owner, attempt, webSocket)
                            return
                        }
                        receivedHello = true
                        lastSequence = 0
                        highestRefreshRequestedSequence = 0
                        failureCount = 0
                        setTransportLocked(owner, Transport.WEBSOCKET)
                        listener.onAuthoritativeRefreshRequired(owner, currentIdentityLocked())
                    }
                    if (!acceptSequenceLocked(owner, envelope.event, envelope.sequence)) return
                    listener.onEvent(owner, currentIdentityLocked(), envelope.event, envelope.data)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                failWebSocket(owner, attempt, webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                failWebSocket(owner, attempt, webSocket)
            }
        }
        val socket = try {
            webSocketFactory.newWebSocket(request, callback)
        } catch (_: Exception) {
            failWebSocket(owner, attempt, null)
            return
        }
        synchronized(this) {
            if (
                !ownsWebSocketAttemptLocked(owner, attempt) ||
                webSocket != null ||
                eventSource != null ||
                eventSourceAttempt != null
            ) {
                socket.cancel()
                return
            }
            webSocket = socket
        }
    }

    private fun failWebSocket(owner: Long, attempt: Any, socket: WebSocket?) {
        synchronized(this) { failWebSocketLocked(owner, attempt, socket) }
    }

    private fun failWebSocketLocked(owner: Long, attempt: Any, socket: WebSocket?) {
        if (!ownsWebSocketAttemptLocked(owner, attempt)) return
        webSocketAttempt = null
        if (webSocket === socket) webSocket = null
        socket?.cancel()
        setTransportLocked(owner, null)
        val available = capabilities ?: return
        scheduler.execute { connectSseOrRetry(owner, available) }
    }

    private fun connectSseOrRetry(owner: Long, available: Capabilities) {
        if (available.sseEnabled) {
            connectSse(owner, available)
        } else {
            synchronized(this) {
                if (ownsLocked(owner)) {
                    capabilities = null
                    scheduleRetryLocked(owner)
                }
            }
        }
    }

    private fun connectSse(owner: Long, available: Capabilities) {
        val current = synchronized(this) { config?.takeIf { ownsLocked(owner) } } ?: return
        val request = Net.get(resolveHttpUrl(current.baseUrl, available.ssePath), current.apiToken)
            .newBuilder()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val attempt = Any()
        val installed = synchronized(this) {
            if (
                !ownsLocked(owner) ||
                webSocket != null ||
                webSocketAttempt != null ||
                eventSource != null ||
                eventSourceAttempt != null
            ) {
                false
            } else {
                eventSourceAttempt = attempt
                true
            }
        }
        if (!installed) return
        val callback = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                synchronized(this@RelayRealtimeClient) {
                    if (!ownsEventSourceAttemptLocked(owner, attempt)) return
                    failureCount = 0
                    setTransportLocked(owner, Transport.SSE)
                    listener.onAuthoritativeRefreshRequired(owner, currentIdentityLocked())
                    scheduleCapabilityRefreshLocked(owner, eventSource)
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val payload = try {
                    JSONObject(data)
                } catch (_: Exception) {
                    return
                }
                val event = type?.trim().orEmpty().ifBlank { payload.optString("type") }
                if (event.isBlank()) return
                synchronized(this@RelayRealtimeClient) {
                    if (!ownsEventSourceAttemptLocked(owner, attempt)) return
                    listener.onEvent(owner, currentIdentityLocked(), event, payload)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                failSse(owner, attempt, eventSource)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.close()
                failSse(owner, attempt, eventSource)
            }
        }
        val created = try {
            eventSourceFactory.newEventSource(request, callback)
        } catch (_: Exception) {
            failSse(owner, attempt, null)
            return
        }
        synchronized(this) {
            if (
                !ownsEventSourceAttemptLocked(owner, attempt) ||
                webSocket != null ||
                webSocketAttempt != null ||
                eventSource != null
            ) {
                created.cancel()
                return
            }
            eventSource = created
        }
    }

    private fun failSse(owner: Long, attempt: Any, source: EventSource?) {
        synchronized(this) {
            if (!ownsEventSourceAttemptLocked(owner, attempt)) return
            eventSourceAttempt = null
            if (eventSource === source) eventSource = null
            source?.cancel()
            capabilityRefreshFuture?.cancel(false)
            capabilityRefreshFuture = null
            capabilities = null
            setTransportLocked(owner, null)
            scheduleRetryLocked(owner)
        }
    }

    private fun scheduleCapabilityRefreshLocked(owner: Long, source: EventSource) {
        capabilityRefreshFuture?.cancel(false)
        capabilityRefreshFuture = scheduler.schedule({
            val shouldDiscover = synchronized(this) {
                if (!ownsLocked(owner) || eventSource !== source) return@schedule
                eventSource = null
                eventSourceAttempt = null
                capabilityRefreshFuture = null
                capabilities = null
                setTransportLocked(owner, null)
                true
            }
            source.cancel()
            if (shouldDiscover) discover(owner)
        }, capabilityRefreshMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleRetryLocked(owner: Long) {
        if (!ownsLocked(owner) || retryFuture?.isDone == false) return
        failureCount += 1
        val exponent = (failureCount - 1).coerceIn(0, 5)
        val base = min(MAX_RETRY_MS, MIN_RETRY_MS shl exponent)
        val delay = (base * Random.nextDouble(0.75, 1.25)).toLong()
        retryFuture = scheduler.schedule({
            val shouldDiscover = synchronized(this) {
                if (!ownsLocked(owner)) return@schedule
                retryFuture = null
                capabilities == null
            }
            if (shouldDiscover) discover(owner) else selectBestTransport(owner)
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun acceptSequenceLocked(owner: Long, event: String, sequence: Long): Boolean {
        if (sequence <= 0) return true
        if (event == "ping") {
            if (sequence > lastSequence) requestSequenceRefreshLocked(owner, sequence)
            return true
        }
        if (lastSequence > 0 && sequence <= lastSequence) return false
        if (lastSequence > 0 && sequence > lastSequence + 1) {
            requestSequenceRefreshLocked(owner, sequence)
        }
        lastSequence = sequence
        return true
    }

    private fun requestSequenceRefreshLocked(owner: Long, sequence: Long) {
        if (sequence <= highestRefreshRequestedSequence) return
        highestRefreshRequestedSequence = sequence
        listener.onAuthoritativeRefreshRequired(owner, currentIdentityLocked())
    }

    private fun setTransportLocked(owner: Long, next: Transport?) {
        if (activeTransport == next) return
        activeTransport = next
        listener.onTransportChanged(owner, currentIdentityLocked(), next)
    }

    private fun currentIdentityLocked(): String = config?.identity.orEmpty()

    private fun ownsLocked(owner: Long): Boolean = !closed && generation == owner && config != null

    private fun ownsWebSocketAttemptLocked(owner: Long, attempt: Any): Boolean =
        ownsLocked(owner) && webSocketAttempt === attempt

    private fun ownsEventSourceAttemptLocked(owner: Long, attempt: Any): Boolean =
        ownsLocked(owner) && eventSourceAttempt === attempt

    private fun retireLocked() {
        capabilityCall?.cancel()
        capabilityCall = null
        webSocket?.cancel()
        webSocket = null
        webSocketAttempt = null
        eventSource?.cancel()
        eventSource = null
        eventSourceAttempt = null
        retryFuture?.cancel(false)
        retryFuture = null
        capabilityRefreshFuture?.cancel(false)
        capabilityRefreshFuture = null
        activeTransport = null
    }

    private fun parseCapabilities(response: Response): Capabilities? {
        if (response.code == 404) {
            return Capabilities(
                websocketEnabled = false,
                websocketPath = "/ui/ws",
                websocketProtocol = REALTIME_SUBPROTOCOL,
                sseEnabled = true,
                ssePath = "/ui/events",
            )
        }
        if (!response.isSuccessful) return null
        val payload = try {
            JSONObject(response.body?.string().orEmpty())
        } catch (_: Exception) {
            return null
        }
        val websocket = payload.optJSONObject("websocket")
        val sse = payload.optJSONObject("sse")
        return Capabilities(
            websocketEnabled = payload.optInt("protocol_version", -1) == PROTOCOL_VERSION &&
                websocket?.optBoolean("enabled", false) == true,
            websocketPath = websocket?.optString("ui", "/ui/ws") ?: "/ui/ws",
            websocketProtocol = websocket?.optString("subprotocol", REALTIME_SUBPROTOCOL)
                ?: REALTIME_SUBPROTOCOL,
            sseEnabled = sse?.optBoolean("enabled", false) == true,
            ssePath = sse?.optString("ui", "/ui/events") ?: "/ui/events",
        )
    }

    companion object {
        const val REALTIME_SUBPROTOCOL = "relaytv.realtime.v1"
        private const val PROTOCOL_VERSION = 1
        private const val MIN_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L
        private const val CAPABILITY_REFRESH_MS = 5 * 60_000L

        fun resolveHttpUrl(baseUrl: String, path: String): String =
            "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

        fun websocketUrl(baseUrl: String, path: String): String {
            val httpUrl = resolveHttpUrl(baseUrl, path)
            return when {
                httpUrl.startsWith("https://", ignoreCase = true) -> "wss://${httpUrl.substring(8)}"
                httpUrl.startsWith("http://", ignoreCase = true) -> "ws://${httpUrl.substring(7)}"
                else -> httpUrl
            }
        }

        fun parseEnvelope(text: String): Envelope? {
            val payload = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            if (payload.optInt("version", -1) != PROTOCOL_VERSION) return null
            val event = payload.optString("event").trim()
            if (event.isBlank()) return null
            val data = payload.optJSONObject("data") ?: return null
            return Envelope(
                version = PROTOCOL_VERSION,
                event = event,
                sequence = payload.optLong("sequence", 0),
                data = data,
            )
        }
    }
}
