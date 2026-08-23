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
            return parse(o)
        }

        fun parse(o: JSONObject): RemoteStatus {

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

    /** Merge the compact realtime playback snapshot over the last full status. */
    fun mergePlayback(patch: JSONObject): RemoteStatus = copy(
        playing = if (patch.has("playing")) patch.optBoolean("playing") else playing,
        paused = if (patch.has("paused")) patch.optBoolean("paused") else paused,
        positionSec = patch.optNullableDouble("position") ?: positionSec,
        durationSec = patch.optNullableDouble("duration") ?: durationSec,
        title = patch.optNullableString("title") ?: title,
        thumbnail = patch.optNullableString("thumbnail_local", "thumbnail", "thumb") ?: thumbnail,
        volumePercent = patch.optNullableDouble("volume")?.toInt()?.coerceIn(0, 100) ?: volumePercent,
    )
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key, Double.NaN).takeUnless { it.isNaN() }
}

private fun JSONObject.optNullableString(vararg keys: String): String? {
    keys.forEach { key ->
        val value = optString(key, "")
        if (value.isNotBlank() && value != "null") return value
    }
    return null
}
