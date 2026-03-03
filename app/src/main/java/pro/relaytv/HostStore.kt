package pro.relaytv

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class RelayHost(
    val id: String,
    val name: String,
    val baseUrl: String,
)

object HostStore {
    private const val PREF = "relaytv_prefs"
    private const val KEY_HOSTS = "hosts_json"
    private const val KEY_ACTIVE = "active_host_id"

    
    fun normalizeBaseUrl(input: String): String? {
        var s = input.trim()
        if (s.isBlank()) return null
        s = s.trimEnd('/')
        // If user entered host:port (no scheme), default to http://
        if (!s.contains("://")) {
            s = "http://" + s
        }
        val url = s.toHttpUrlOrNull() ?: return null
        return url.toString().trimEnd('/')
    }

private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    @Suppress("UNUSED_PARAMETER")
    fun ensureMigrated(ctx: Context) {
        // no-op
    }

    fun loadHosts(ctx: Context): List<RelayHost> {
        ensureMigrated(ctx)
        val raw = prefs(ctx).getString(KEY_HOSTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        RelayHost(
                            id = o.optString("id"),
                            name = o.optString("name", "Server"),
                            baseUrl = normalizeBaseUrl(o.optString("baseUrl")) ?: "",
                        )
                    )
                }
            }.filter { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveHosts(ctx: Context, hosts: List<RelayHost>) {
        val arr = JSONArray()
        hosts.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("baseUrl", normalizeBaseUrl(it.baseUrl) ?: "")
            )
        }
        prefs(ctx).edit().putString(KEY_HOSTS, arr.toString()).apply()
    }

    fun getActiveHostId(ctx: Context): String? {
        ensureMigrated(ctx)
        return prefs(ctx).getString(KEY_ACTIVE, null)
    }

    fun setActiveHostId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun getActiveHost(ctx: Context): RelayHost? {
        val hosts = loadHosts(ctx)
        if (hosts.isEmpty()) return null
        val active = getActiveHostId(ctx)
        val hit = hosts.firstOrNull { it.id == active } ?: hosts.first()
        if (hit.id != active) setActiveHostId(ctx, hit.id)
        return hit
    }

    fun getActiveBaseUrl(ctx: Context): String? {
        val hit = getActiveHost(ctx) ?: return null
        return normalizeBaseUrl(hit.baseUrl) ?: ""
    }

    fun upsert(ctx: Context, host: RelayHost) {
        val hosts = loadHosts(ctx).toMutableList()
        val idx = hosts.indexOfFirst { it.id == host.id }
        if (idx >= 0) hosts[idx] = host else hosts.add(host)
        saveHosts(ctx, hosts)
    }

    fun remove(ctx: Context, id: String) {
        val hosts = loadHosts(ctx).filterNot { it.id == id }
        saveHosts(ctx, hosts)
        val active = getActiveHostId(ctx)
        if (active == id) {
            val next = hosts.firstOrNull()
            if (next != null) setActiveHostId(ctx, next.id) else prefs(ctx).edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun create(ctx: Context, name: String, baseUrl: String): RelayHost {
        val norm = normalizeBaseUrl(baseUrl) ?: ""
        val host = RelayHost(UUID.randomUUID().toString(), name.ifBlank { "Server" }, norm)
        upsert(ctx, host)
        if (getActiveHostId(ctx).isNullOrBlank()) setActiveHostId(ctx, host.id)
        return host
    }
}
