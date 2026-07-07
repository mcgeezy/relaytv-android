package pro.relaytv

import org.json.JSONObject

/**
 * Snapshot of the active server's playback state, parsed from GET /status.
 * Parsing is intentionally defensive across RelayTV server versions
 * (mirrors the field fallbacks used by the Home Assistant integration).
 */
data class RemoteStatus(
    val playing: Boolean = false,
    val paused: Boolean = false,
    val positionSec: Double? = null,
    val durationSec: Double? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val volumePercent: Int? = null,
) {
    val active: Boolean get() = playing || paused

    companion object {
        val IDLE = RemoteStatus()

        fun parse(body: String): RemoteStatus {
            val o = try {
                JSONObject(body)
            } catch (_: Exception) {
                return IDLE
            }

            fun num(vararg keys: String): Double? {
                for (k in keys) {
                    if (o.has(k) && !o.isNull(k)) {
                        val v = o.optDouble(k, Double.NaN)
                        if (!v.isNaN()) return v
                    }
                }
                return null
            }

            val np = o.optJSONObject("now_playing") ?: o.optJSONObject("media")

            fun str(obj: JSONObject?, vararg keys: String): String? {
                if (obj == null) return null
                for (k in keys) {
                    val v = obj.optString(k, "")
                    if (v.isNotBlank() && v != "null") return v
                }
                return null
            }

            return RemoteStatus(
                playing = o.optBoolean("playing") || o.optBoolean("is_playing") || o.optBoolean("play"),
                paused = o.optBoolean("paused") || o.optBoolean("is_paused") || o.optBoolean("pause"),
                positionSec = num("position", "pos", "time"),
                durationSec = num("duration", "len", "total"),
                title = str(np, "title", "name") ?: str(o, "title"),
                thumbnail = str(np, "thumbnail_local", "thumbnail", "thumb")
                    ?: str(o, "thumbnail_local", "thumbnail", "thumb"),
                volumePercent = num("volume", "vol")?.let { it.toInt().coerceIn(0, 100) },
            )
        }
    }
}
