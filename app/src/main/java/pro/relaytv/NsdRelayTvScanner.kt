package pro.relaytv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

data class DiscoveredRelayTvServer(
    val name: String,
    val baseUrl: String,
    val path: String,
)

class NsdRelayTvScanner(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var timeoutRunnable: Runnable? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        private const val SERVICE_TYPE = "_relaytv._tcp."
    }

    interface Callback {
        fun onUpdate(servers: List<DiscoveredRelayTvServer>)
        fun onFinished(servers: List<DiscoveredRelayTvServer>)
        fun onError(message: String)
    }

    fun scan(timeoutMs: Long = 8_000L, callback: Callback) {
        stop()

        val found = linkedMapOf<String, DiscoveredRelayTvServer>()
        var finished = false

        fun sortedServers(): List<DiscoveredRelayTvServer> =
            found.values.sortedWith(compareBy({ it.name.lowercase() }, { it.baseUrl }))

        fun emitUpdate() {
            val snapshot = sortedServers()
            uiHandler.post { callback.onUpdate(snapshot) }
        }

        fun emitError(message: String) {
            uiHandler.post { callback.onError(message) }
        }

        fun finish() {
            if (finished) return
            finished = true
            val snapshot = sortedServers()
            uiHandler.post { callback.onFinished(snapshot) }
        }

        fun upsertResolved(info: NsdServiceInfo) {
            val attributes = info.attributes
            val serviceTag = attributes?.get("service")?.toUtf8()?.lowercase()
            if (!serviceTag.isNullOrBlank() && serviceTag != "relaytv") return

            val ip = nsdHostAddress(info)
            if (ip.isBlank()) return
            val host = if (ip.contains(":")) "[$ip]" else ip
            val base = HostStore.normalizeBaseUrl("http://$host:${info.port}") ?: return
            val path = attributes?.get("path")?.toUtf8()?.ifBlank { "/ui" } ?: "/ui"
            val name = info.serviceName.trim().ifBlank { "RelayTV" }

            val server = DiscoveredRelayTvServer(name = name, baseUrl = base, path = path)
            found[base] = server
            emitUpdate()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                emitError("mDNS discovery failed to start ($errorCode)")
                stop()
                finish()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                emitError("mDNS discovery failed to stop cleanly ($errorCode)")
                stop()
                finish()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                finish()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.equals(SERVICE_TYPE, ignoreCase = true)) return

                resolveServiceCompat(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        uiHandler.post { upsertResolved(serviceInfo) }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val ip = nsdHostAddress(serviceInfo)
                if (ip.isBlank()) return
                val host = if (ip.contains(":")) "[$ip]" else ip
                val base = HostStore.normalizeBaseUrl("http://$host:${serviceInfo.port}") ?: return
                if (found.remove(base) != null) {
                    emitUpdate()
                }
            }
        }

        discoveryListener = listener
        acquireMulticastLock()
        val started = runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.isSuccess
        if (!started) {
            emitError("Unable to start LAN discovery.")
            stop()
            finish()
            return
        }

        timeoutRunnable = Runnable { stop() }.also { uiHandler.postDelayed(it, timeoutMs) }
    }

    fun stop() {
        timeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        timeoutRunnable = null

        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("relaytv-mdns-scan").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceCompat(serviceInfo: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        nsdManager.resolveService(serviceInfo, listener)
    }

    @Suppress("DEPRECATION")
    private fun nsdHostAddress(serviceInfo: NsdServiceInfo): String =
        (serviceInfo.host?.hostAddress ?: "").trim().substringBefore('%')
}

private fun ByteArray.toUtf8(): String = toString(Charsets.UTF_8).trim()
