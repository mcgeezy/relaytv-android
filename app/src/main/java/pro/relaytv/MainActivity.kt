package pro.relaytv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    private lateinit var web: WebView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var activeBaseUrl: String? = null
    private var consecutiveHealthFailures = 0
    private var healthRequestInFlight = false
    private var mainFrameFailed = false
    private var initialLoadComplete = false
    private var networkCallbackRegistered = false
    private var isInForeground = false
    private var mdnsScanner: NsdRelayTvScanner? = null

    private val heartbeatRunnable = Runnable { runHeartbeat() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { scheduleHeartbeat(500) }
        }
    }

    companion object {
        private const val HEARTBEAT_OK_MS = 15_000L
        private const val HEARTBEAT_BASE_RETRY_MS = 4_000L
        private const val HEARTBEAT_MAX_RETRY_MS = 60_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 13+ requires runtime permission for notifications.
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContentView(R.layout.activity_main)
        val openServers = intent.getBooleanExtra("open_servers", false)
        toolbar = findViewById(R.id.toolbar)
        web = findViewById(R.id.web)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (this@MainActivity::web.isInitialized && web.canGoBack()) {
                    web.goBack()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_servers -> {
                    showServerPicker()
                    true
                }
                R.id.action_reload -> {
                    loadActiveServer(forcePickerOnFailure = false, manualRefresh = true)
                    true
                }
                else -> false
            }
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.userAgentString = web.settings.userAgentString + " RelayTV/1.1.0"
        swipeRefresh.setOnRefreshListener {
            loadActiveServer(forcePickerOnFailure = false, manualRefresh = true)
        }

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                mainFrameFailed = false
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this@MainActivity, "Connection lost. Retrying…", Toast.LENGTH_SHORT).show()
                    scheduleHeartbeat(1_500)
                }
            }
        }

        val base = HostStore.getActiveBaseUrl(this)
        if (openServers) {
            showServerPicker(force = base.isNullOrBlank())
        }

        if (base.isNullOrBlank()) {
            showServerPicker(force = true)
            return
        }
        loadServerBase(base.trimEnd('/'), forcePickerOnFailure = true, manualRefresh = false)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        registerNetworkCallback()
        if (!activeBaseUrl.isNullOrBlank()) {
            scheduleHeartbeat(2_000)
        }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        unregisterNetworkCallback()
        uiHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        mdnsScanner?.stop()
        super.onDestroy()
    }

    private fun loadServerBase(base: String, forcePickerOnFailure: Boolean, manualRefresh: Boolean) {
        val normalized = base.trimEnd('/')
        if (normalized.isBlank()) {
            swipeRefresh.isRefreshing = false
            return
        }
        activeBaseUrl = normalized
        if (manualRefresh || !initialLoadComplete) {
            swipeRefresh.isRefreshing = true
        }
        probeServerHealth(
            base = normalized,
            forcePickerOnFailure = forcePickerOnFailure,
            loadUiOnSuccess = true,
            manualRefresh = manualRefresh
        )
    }

    private fun loadActiveServer(forcePickerOnFailure: Boolean, manualRefresh: Boolean) {
        val base = activeBaseUrl ?: HostStore.getActiveBaseUrl(this)?.trimEnd('/')
        if (base.isNullOrBlank()) {
            swipeRefresh.isRefreshing = false
            showServerPicker(force = true)
            return
        }
        loadServerBase(base, forcePickerOnFailure = forcePickerOnFailure, manualRefresh = manualRefresh)
    }

    private fun probeServerHealth(
        base: String,
        forcePickerOnFailure: Boolean,
        loadUiOnSuccess: Boolean,
        manualRefresh: Boolean,
        fromHeartbeat: Boolean = false,
    ) {
        if (healthRequestInFlight) return
        if (fromHeartbeat && !isInForeground) return

        val req = Net.get(base + "/health")
        healthRequestInFlight = true
        Net.client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    healthRequestInFlight = false
                    onServerHealthFailed(
                        forcePickerOnFailure = forcePickerOnFailure,
                        manualRefresh = manualRefresh
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val healthy = response.use { it.isSuccessful }
                runOnUiThread {
                    healthRequestInFlight = false
                    if (!healthy) {
                        onServerHealthFailed(
                            forcePickerOnFailure = forcePickerOnFailure,
                            manualRefresh = manualRefresh
                        )
                        return@runOnUiThread
                    }
                    val recovered = consecutiveHealthFailures > 0
                    consecutiveHealthFailures = 0
                    if (recovered && !manualRefresh) {
                        Toast.makeText(this@MainActivity, "Reconnected to RelayTV.", Toast.LENGTH_SHORT).show()
                    }

                    if (loadUiOnSuccess) {
                        val uiUrl = "$base/ui"
                        val shouldLoad = manualRefresh || mainFrameFailed || web.url.isNullOrBlank() || !web.url.orEmpty().startsWith(uiUrl)
                        if (shouldLoad) {
                            web.loadUrl(uiUrl)
                        }
                    }

                    mainFrameFailed = false
                    initialLoadComplete = true
                    swipeRefresh.isRefreshing = false
                    scheduleHeartbeat(HEARTBEAT_OK_MS)
                }
            }
        })
    }

    private fun onServerHealthFailed(forcePickerOnFailure: Boolean, manualRefresh: Boolean) {
        consecutiveHealthFailures += 1
        swipeRefresh.isRefreshing = false

        if (forcePickerOnFailure && !initialLoadComplete) {
            Toast.makeText(this, "Server not reachable. Switch servers.", Toast.LENGTH_LONG).show()
            showServerPicker(force = true)
            return
        }

        if (manualRefresh) {
            Toast.makeText(this, "Can't reach server right now.", Toast.LENGTH_SHORT).show()
        } else if (consecutiveHealthFailures == 1) {
            Toast.makeText(this, "Connection lost. Retrying…", Toast.LENGTH_SHORT).show()
        }
        scheduleHeartbeat(nextRetryDelayMs())
    }

    private fun nextRetryDelayMs(): Long {
        val cappedPower = min(4, (consecutiveHealthFailures - 1).coerceAtLeast(0))
        val delay = HEARTBEAT_BASE_RETRY_MS * (1L shl cappedPower)
        return min(delay, HEARTBEAT_MAX_RETRY_MS)
    }

    private fun runHeartbeat() {
        if (!isInForeground) return
        val base = activeBaseUrl ?: HostStore.getActiveBaseUrl(this)?.trimEnd('/') ?: return
        probeServerHealth(
            base = base,
            forcePickerOnFailure = false,
            loadUiOnSuccess = mainFrameFailed || web.url.isNullOrBlank(),
            manualRefresh = false,
            fromHeartbeat = true
        )
    }

    private fun scheduleHeartbeat(delayMs: Long) {
        uiHandler.removeCallbacks(heartbeatRunnable)
        if (!isInForeground) return
        if (activeBaseUrl.isNullOrBlank()) return
        uiHandler.postDelayed(heartbeatRunnable, delayMs)
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
    }

    private fun upsertDiscoveredServer(server: DiscoveredRelayTvServer): RelayHost {
        val normalized = HostStore.normalizeBaseUrl(server.baseUrl) ?: server.baseUrl
        val hosts = HostStore.loadHosts(this)
        val existing = hosts.firstOrNull {
            HostStore.normalizeBaseUrl(it.baseUrl) == normalized
        }
        return if (existing != null) {
            existing
        } else {
            HostStore.create(this, server.name.ifBlank { "RelayTV" }, normalized)
        }
    }

    private fun showDiscoveredServersDialog(
        servers: List<DiscoveredRelayTvServer>,
        onPick: (DiscoveredRelayTvServer) -> Unit,
    ) {
        val labels = servers.map { "${it.name}  •  ${it.baseUrl}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.discovered_servers))
            .setItems(labels) { _, which ->
                val chosen = servers.getOrNull(which) ?: return@setItems
                onPick(chosen)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showServerPicker(force: Boolean = false) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_server_picker, null)
        val list = view.findViewById<ListView>(R.id.listServers)
        val btnAdd = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAdd)
        val btnEdit = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEdit)
        val btnRemove = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRemove)
        val btnDiscover = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDiscover)
        val txtActiveServer = view.findViewById<android.widget.TextView>(R.id.txtActiveServer)

        val hostStatuses = mutableMapOf<String, String>()
        val statusChecksInFlight = mutableSetOf<String>()
        lateinit var refresh: (String?) -> Unit

        fun selectedHostIdFromList(): String? {
            val hosts = HostStore.loadHosts(this)
            val pos = list.checkedItemPosition
            return hosts.getOrNull(pos)?.id
        }

        fun renderActiveServer(selectionId: String?) {
            val hosts = HostStore.loadHosts(this)
            val active = hosts.firstOrNull { it.id == selectionId }
                ?: hosts.firstOrNull { it.id == HostStore.getActiveHostId(this) }
                ?: hosts.firstOrNull()
            txtActiveServer.text = if (active != null) {
                "${active.name}\n${active.baseUrl}"
            } else {
                getString(R.string.no_server_selected)
            }
        }

        fun statusLabelFor(host: RelayHost): String {
            return hostStatuses[host.id] ?: getString(R.string.status_checking)
        }

        fun scheduleHostStatusCheck(host: RelayHost) {
            if (statusChecksInFlight.contains(host.id)) return
            if (hostStatuses.containsKey(host.id)) return
            statusChecksInFlight.add(host.id)
            hostStatuses[host.id] = getString(R.string.status_checking)

            val req = Net.get(host.baseUrl.trimEnd('/') + "/health")
            Net.client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        statusChecksInFlight.remove(host.id)
                        hostStatuses[host.id] = getString(R.string.status_offline)
                        refresh(selectedHostIdFromList() ?: HostStore.getActiveHostId(this@MainActivity))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val ok = response.use { it.isSuccessful }
                    runOnUiThread {
                        statusChecksInFlight.remove(host.id)
                        hostStatuses[host.id] = if (ok) {
                            getString(R.string.status_online)
                        } else {
                            getString(R.string.status_offline)
                        }
                        refresh(selectedHostIdFromList() ?: HostStore.getActiveHostId(this@MainActivity))
                    }
                }
            })
        }

        refresh = { selectionIdInput ->
            val selectionId = selectionIdInput ?: HostStore.getActiveHostId(this)
            val hosts = HostStore.loadHosts(this)
            val activeId = HostStore.getActiveHostId(this)
            val labels = hosts.map { host ->
                val prefix = if (host.id == activeId) {
                    getString(R.string.status_active_prefix) + " • "
                } else {
                    ""
                }
                "${host.name}  •  ${prefix}${statusLabelFor(host)}\n${host.baseUrl}"
            }
            list.adapter = ArrayAdapter(this, R.layout.item_server_picker_choice, android.R.id.text1, labels)
            list.choiceMode = ListView.CHOICE_MODE_SINGLE
            val idx = hosts.indexOfFirst { it.id == selectionId }.let { if (it >= 0) it else 0 }
            if (hosts.isNotEmpty()) list.setItemChecked(idx, true)
            btnEdit.isEnabled = hosts.isNotEmpty()
            btnRemove.isEnabled = hosts.isNotEmpty()
            renderActiveServer(selectionId)
            hosts.forEach { scheduleHostStatusCheck(it) }
        }

        refresh(HostStore.getActiveHostId(this))
        btnDiscover.text = getString(R.string.scan_lan)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.select_server))
            .setView(view)
            .setNegativeButton(if (force) "Exit" else "Close") { d, _ ->
                d.dismiss()
                if (force && HostStore.getActiveBaseUrl(this).isNullOrBlank()) {
                    finish()
                }
            }
            .setPositiveButton("Use") { d, _ ->
                val hosts = HostStore.loadHosts(this)
                if (hosts.isEmpty()) {
                    Toast.makeText(this, "Add a server first.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val pos = list.checkedItemPosition.coerceAtLeast(0)
                val chosen = hosts.getOrNull(pos) ?: hosts.first()
                HostStore.setActiveHostId(this, chosen.id)
                toolbar.subtitle = chosen.name
                loadServerBase(chosen.baseUrl, forcePickerOnFailure = true, manualRefresh = false)
                d.dismiss()
            }
            .create()
        dialog.setOnDismissListener {
            mdnsScanner?.stop()
            btnDiscover.text = getString(R.string.scan_lan)
            btnDiscover.isEnabled = true
        }

        btnDiscover.setOnClickListener {
            btnDiscover.isEnabled = false
            btnDiscover.text = getString(R.string.scanning_lan)

            val scanner = mdnsScanner ?: NsdRelayTvScanner(this).also { mdnsScanner = it }
            scanner.scan(callback = object : NsdRelayTvScanner.Callback {
                override fun onUpdate(servers: List<DiscoveredRelayTvServer>) = Unit

                override fun onFinished(servers: List<DiscoveredRelayTvServer>) {
                    btnDiscover.text = getString(R.string.scan_lan)
                    btnDiscover.isEnabled = true
                    if (!dialog.isShowing) return
                    if (servers.isEmpty()) {
                        Toast.makeText(this@MainActivity, getString(R.string.discovery_none), Toast.LENGTH_SHORT).show()
                        return
                    }
                    showDiscoveredServersDialog(servers) { selected ->
                        val host = upsertDiscoveredServer(selected)
                        HostStore.setActiveHostId(this@MainActivity, host.id)
                        toolbar.subtitle = host.name
                        hostStatuses.remove(host.id)
                        statusChecksInFlight.remove(host.id)
                        refresh(host.id)
                        loadServerBase(host.baseUrl, forcePickerOnFailure = false, manualRefresh = false)
                        dialog.dismiss()
                    }
                }

                override fun onError(message: String) {
                    btnDiscover.text = getString(R.string.scan_lan)
                    btnDiscover.isEnabled = true
                    if (!dialog.isShowing) return
                    val msg = message.ifBlank { getString(R.string.discovery_failed) }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            })
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val hosts = HostStore.loadHosts(this)
            val chosen = hosts.getOrNull(position) ?: return@setOnItemClickListener
            toolbar.subtitle = chosen.name
            renderActiveServer(chosen.id)
        }
        fun showAddEdit(existing: RelayHost? = null) {
            val nameInput = EditText(this).apply {
                hint = getString(R.string.server_name)
                setText(existing?.name ?: "")
            }
            val urlInput = EditText(this).apply {
                hint = getString(R.string.server_url)
                setText(existing?.baseUrl ?: "")
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
                addView(nameInput)
                addView(urlInput)
            }

            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle(if (existing == null) getString(R.string.add_server) else getString(R.string.edit_server))
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                // We override the positive click to keep the dialog open on validation errors.
                .setPositiveButton("Save", null)
                .create()

            dlg.setOnShowListener {
                val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                btn.setOnClickListener {
                    val name = nameInput.text.toString().trim().ifBlank { "Server" }
                    val raw = urlInput.text.toString()
                    val base = HostStore.normalizeBaseUrl(raw)

                    if (base.isNullOrBlank()) {
                        Toast.makeText(this, "Enter a valid base URL (example: http://10.0.55.2:8787).", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    btn.isEnabled = false
                    Toast.makeText(this, "Verifying server…", Toast.LENGTH_SHORT).show()

                    val req = Net.get(base + "/health")
                    Net.client.newCall(req).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            runOnUiThread {
                                btn.isEnabled = true
                                Toast.makeText(this@MainActivity, "Can't reach server. Check address.", Toast.LENGTH_LONG).show()
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val ok = response.isSuccessful && try {
                                val body = response.body?.string() ?: ""
                                val o = org.json.JSONObject(body)
                                o.optBoolean("ok", false)
                            } catch (_: Exception) { false }

                            runOnUiThread {
                                if (!ok) {
                                    btn.isEnabled = true
                                    Toast.makeText(this@MainActivity, "Not a RelayTV server (check /health).", Toast.LENGTH_LONG).show()
                                    return@runOnUiThread
                                }

                                val host = if (existing == null) {
                                    HostStore.create(this@MainActivity, name, base)
                                } else {
                                    val updated = existing.copy(name = name, baseUrl = base)
                                    HostStore.upsert(this@MainActivity, updated)
                                    updated
                                }

                                HostStore.setActiveHostId(this@MainActivity, host.id)
                                hostStatuses.remove(host.id)
                                statusChecksInFlight.remove(host.id)
                                refresh(host.id)
                                dlg.dismiss()
                            }
                        }
                    })
                }
            }

            dlg.show()
        }


        btnAdd.setOnClickListener { showAddEdit(null) }

        btnEdit.setOnClickListener {
            val hosts = HostStore.loadHosts(this)
            val pos = list.checkedItemPosition.coerceAtLeast(0)
            val existing = hosts.getOrNull(pos) ?: return@setOnClickListener
            showAddEdit(existing)
        }

        btnRemove.setOnClickListener {
            val hosts = HostStore.loadHosts(this)
            val pos = list.checkedItemPosition.coerceAtLeast(0)
            val existing = hosts.getOrNull(pos) ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove server?")
                .setMessage("Remove ${existing.name}?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    HostStore.remove(this, existing.id)
                    hostStatuses.remove(existing.id)
                    refresh(HostStore.getActiveHostId(this))
                }
                .show()
        }

        // Show active server on toolbar
        HostStore.loadHosts(this).firstOrNull { it.id == HostStore.getActiveHostId(this) }?.let {
            toolbar.subtitle = it.name
        }

        dialog.show()
    }
}
