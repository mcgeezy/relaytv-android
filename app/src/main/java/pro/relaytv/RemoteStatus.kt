package pro.relaytv

import org.json.JSONObject

data class RemoteDvrStatus(
    val supported: Boolean = false,
    val seekable: Boolean = false,
    val bufferStartSec: Double? = null,
    val liveEdgeSec: Double? = null,
) {
    companion object {
        fun parse(o: JSONObject?): RemoteDvrStatus? {
            if (o == null) return null
            if (!o.optBoolean("supported", false)) return null
            val seekable = o.optBoolean("seekable", false)
            val bufferStart = o.optNullableDouble("buffer_start")?.coerceAtLeast(0.0)
            val liveEdge = (o.optNullableDouble("live_edge") ?: o.optNullableDouble("buffer_end"))
                ?.takeIf { it > 0.0 }
            return RemoteDvrStatus(
                supported = true,
                seekable = seekable,
                bufferStartSec = bufferStart,
                liveEdgeSec = liveEdge,
            )
        }
    }
}

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
    val dvr: RemoteDvrStatus? = null,
) {
    val active: Boolean get() = playing || paused

    val effectiveDurationSec: Double?
        get() = when {
            dvr?.supported == true -> if (dvr.seekable) {
                (dvr.liveEdgeSec ?: durationSec)?.takeIf { it > 0.0 }
            } else {
                null
            }
            else -> durationSec?.takeIf { it > 0.0 }
        }

    val isSeekable: Boolean
        get() = when {
            dvr?.supported == true -> dvr.seekable && effectiveDurationSec != null
            else -> effectiveDurationSec != null
        }

    fun clampSeekSec(targetSec: Double): Double {
        val coerced = targetSec.coerceAtLeast(0.0)
        val liveDvr = dvr?.takeIf { it.supported && it.seekable } ?: return coerced
        val minSec = liveDvr.bufferStartSec ?: 0.0
        val maxSec = liveDvr.liveEdgeSec ?: effectiveDurationSec ?: Double.MAX_VALUE
        return if (maxSec >= minSec) coerced.coerceIn(minSec, maxSec) else coerced.coerceAtLeast(minSec)
    }

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
                dvr = RemoteDvrStatus.parse(o.optJSONObject("dvr")),
            )
        }
    }

    /** Merge the compact realtime playback snapshot over the last full status. */
    fun mergePlayback(patch: JSONObject): RemoteStatus {
        val nextPlaying = if (patch.has("playing")) patch.optBoolean("playing") else playing
        val nextPaused = if (patch.has("paused")) patch.optBoolean("paused") else paused
        val clearWhenIdle = !nextPlaying && !nextPaused
        val clearNowPlaying = patch.has("has_now_playing") &&
            !patch.optBoolean("has_now_playing") &&
            clearWhenIdle

        fun mergeMetric(key: String, current: Double?): Double? {
            if (!patch.has(key)) return current
            val next = patch.optNullableDouble(key)
            return if (next != null || !nextPlaying || !playing) next else current
        }

        val nextDvr = if (patch.has("dvr")) {
            RemoteDvrStatus.parse(patch.optJSONObject("dvr"))
        } else if (clearWhenIdle) {
            null
        } else {
            dvr
        }

        return copy(
            playing = nextPlaying,
            paused = nextPaused,
            positionSec = mergeMetric("position", positionSec),
            durationSec = mergeMetric("duration", durationSec),
            title = patch.optNullableString("title") ?: if (clearNowPlaying) null else title,
            thumbnail = patch.optNullableString("thumbnail_local", "thumbnail", "thumb")
                ?: if (clearNowPlaying) null else thumbnail,
            volumePercent = patch.optNullableDouble("volume")?.toInt()?.coerceIn(0, 100) ?: volumePercent,
            dvr = nextDvr,
        )
    }
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
