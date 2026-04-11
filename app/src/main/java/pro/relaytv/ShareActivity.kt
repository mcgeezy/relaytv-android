package pro.relaytv

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.UUID

class ShareActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RelayTVShare"
        private const val META_ENDPOINT_PATH = "pro.relaytv.SHARE_ENDPOINT_PATH"
        private const val META_SUCCESS_TEMPLATE = "pro.relaytv.SHARE_SUCCESS_TEMPLATE"
        private val SUPPORTED_MEDIA_MIME_TYPES = setOf(
            "application/octet-stream",
            "application/ogg",
            "audio/aac",
            "audio/flac",
            "audio/m4a",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "audio/opus",
            "audio/wav",
            "audio/wave",
            "audio/x-aac",
            "audio/x-flac",
            "audio/x-m4a",
            "audio/x-wav",
            "video/mp4",
            "video/webm",
        )
    }

    private sealed class SharePayload {
        data class RemoteUrl(val url: String) : SharePayload()
        data class LocalMedia(val filePath: String, val mimeType: String, val title: String) : SharePayload()
    }

    private data class LocalMediaStageResult(
        val payload: SharePayload.LocalMedia? = null,
        val errorMessageResId: Int? = null,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val base = HostStore.getActiveBaseUrl(this)?.trim()?.trimEnd('/')
        if (base.isNullOrBlank()) {
            // No server selected yet; open app so user can configure.
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("open_servers", true)
            })
            finish()
            return
        }

        val endpointPath = resolveMetaString(META_ENDPOINT_PATH).ifBlank { "/smart" }
        val successTemplate = resolveMetaString(META_SUCCESS_TEMPLATE).ifBlank { "Sent to \"%s\"" }
        val streamUri = extractStreamUri()
        if (streamUri != null) {
            Thread {
                val result = stageLocalMedia(streamUri)
                runOnUiThread {
                    if (result.payload != null) {
                        dispatchPayload(base, endpointPath, successTemplate, result.payload)
                    } else if (result.errorMessageResId != null) {
                        Toast.makeText(this, getString(result.errorMessageResId), Toast.LENGTH_LONG).show()
                    }
                    finishAndRemoveTask()
                }
            }.start()
            return
        }

        val payload = extractRemoteUrlPayload() ?: run { finish(); return }
        dispatchPayload(base, endpointPath, successTemplate, payload)
        finishAndRemoveTask()
    }

    private fun dispatchPayload(
        base: String,
        endpointPath: String,
        successTemplate: String,
        payload: SharePayload,
    ) {
        val inputBuilder = Data.Builder()
            .putString(ShareWorker.KEY_BASE, base)
            .putString(ShareWorker.KEY_ENDPOINT_PATH, endpointPath)

        val toastTemplate = when (payload) {
            is SharePayload.RemoteUrl -> successTemplate
            is SharePayload.LocalMedia -> {
                if (endpointPath == "/play_now") {
                    getString(R.string.share_media_play_started)
                } else {
                    getString(R.string.share_media_queue_started)
                }
            }
        }

        when (payload) {
            is SharePayload.RemoteUrl -> {
                inputBuilder.putString(ShareWorker.KEY_URL, payload.url)
            }
            is SharePayload.LocalMedia -> {
                inputBuilder
                    .putString(ShareWorker.KEY_UPLOAD_FILE_PATH, payload.filePath)
                    .putString(ShareWorker.KEY_UPLOAD_MIME_TYPE, payload.mimeType)
                    .putString(ShareWorker.KEY_UPLOAD_TITLE, payload.title)
            }
        }

        val req = OneTimeWorkRequestBuilder<ShareWorker>()
            .setInputData(inputBuilder.build())
            .build()

        WorkManager.getInstance(this).enqueue(req)
        val serverName = HostStore.getActiveHost(this)?.name?.ifBlank { "Server" } ?: "Server"
        Toast.makeText(this, toastTemplate.format(serverName), Toast.LENGTH_SHORT).show()
    }

    private fun resolveMetaString(key: String): String {
        return try {
            val flags = PackageManager.GET_META_DATA
            val info = packageManager.getActivityInfo(componentName, flags)
            info.metaData?.getString(key).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractUrl(text: String): String? {
        val m = Regex("""https?://\S+""").find(text)
        return m?.value?.trim()?.trimEnd(')', ']', '>', '"', '\'')
    }

    private fun extractRemoteUrlPayload(): SharePayload? {
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = extractUrl(shared) ?: return null
        return SharePayload.RemoteUrl(url)
    }

    private fun extractStreamUri(): Uri? {
        @Suppress("DEPRECATION")
        val extra = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return extra
    }

    private fun stageLocalMedia(uri: Uri): LocalMediaStageResult {
        val rawMimeType = contentResolver.getType(uri)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: ""
        val displayName = queryDisplayName(uri)
        val inferredMimeType = normalizeSharedMimeType(rawMimeType, displayName)
        Log.i(
            TAG,
            "stageLocalMedia uri=$uri rawMime=$rawMimeType inferredMime=$inferredMimeType displayName=$displayName"
        )
        if (inferredMimeType !in SUPPORTED_MEDIA_MIME_TYPES) {
            return LocalMediaStageResult(errorMessageResId = R.string.share_media_unsupported)
        }

        val staged = runCatching {
            copyUriToCache(uri, displayName, inferredMimeType)
        }.getOrNull()

        if (staged == null) {
            return LocalMediaStageResult(errorMessageResId = R.string.share_media_read_failed)
        }

        val title = staged.nameWithoutExtension.ifBlank { displayName.substringBeforeLast('.', displayName) }
        return LocalMediaStageResult(
            payload = SharePayload.LocalMedia(
                filePath = staged.absolutePath,
                mimeType = inferredMimeType,
                title = title
            )
        )
    }

    private fun copyUriToCache(uri: Uri, displayName: String, mimeType: String): File {
        pruneStagedFiles()
        val dir = File(cacheDir, "shared-media").apply { mkdirs() }
        val ext = when {
            displayName.contains('.') -> ".${displayName.substringAfterLast('.')}"
            mimeType == "audio/mpeg" -> ".mp3"
            mimeType == "audio/mp4" || mimeType == "audio/m4a" || mimeType == "audio/x-m4a" -> ".m4a"
            mimeType == "audio/aac" || mimeType == "audio/x-aac" -> ".aac"
            mimeType == "audio/wav" || mimeType == "audio/wave" || mimeType == "audio/x-wav" -> ".wav"
            mimeType == "audio/flac" || mimeType == "audio/x-flac" -> ".flac"
            mimeType == "audio/ogg" || mimeType == "application/ogg" -> ".ogg"
            mimeType == "audio/opus" -> ".opus"
            mimeType == "video/webm" -> ".webm"
            else -> ".mp4"
        }
        val baseName = sanitizeFilename(displayName.substringBeforeLast('.', displayName))
        val outFile = File(dir, "${baseName}-${UUID.randomUUID().toString().take(8)}${ext.lowercase()}")
        contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
                output.flush()
            }
        } ?: error("No readable input stream for shared media")
        return outFile
    }

    private fun queryDisplayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "upload"
        val cursor: Cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: return fallback
        cursor.use {
            if (!it.moveToFirst()) return fallback
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx < 0) return fallback
            return it.getString(idx)?.takeIf { value -> value.isNotBlank() } ?: fallback
        }
    }

    private fun sanitizeFilename(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_')
        return if (cleaned.isBlank()) "relaytv-upload" else cleaned.take(96)
    }

    private fun normalizeSharedMimeType(rawMimeType: String, displayName: String): String {
        val byExtension = when {
            displayName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            displayName.endsWith(".m4a", ignoreCase = true) -> "audio/m4a"
            displayName.endsWith(".aac", ignoreCase = true) -> "audio/aac"
            displayName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            displayName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
            displayName.endsWith(".ogg", ignoreCase = true) -> "application/ogg"
            displayName.endsWith(".opus", ignoreCase = true) -> "audio/opus"
            else -> ""
        }
        if (byExtension.isNotBlank()) return byExtension

        val byMime = when (rawMimeType) {
            "application/octet-stream" -> "application/octet-stream"
            "application/ogg" -> "application/ogg"
            "audio/aac", "audio/x-aac" -> "audio/aac"
            "audio/flac", "audio/x-flac" -> "audio/flac"
            "audio/m4a" -> "audio/m4a"
            "audio/mpeg", "audio/mp3", "audio/x-mp3" -> "audio/mpeg"
            "audio/mp4" -> "audio/mp4"
            "audio/ogg" -> "audio/ogg"
            "audio/opus" -> "audio/opus"
            "audio/wav", "audio/wave", "audio/x-wav" -> "audio/wav"
            "audio/x-m4a" -> "audio/x-m4a"
            "video/mp4" -> "video/mp4"
            "video/webm" -> "video/webm"
            else -> ""
        }
        if (byMime.isNotBlank()) return byMime

        return when {
            displayName.endsWith(".mp4", ignoreCase = true) || displayName.endsWith(".m4v", ignoreCase = true) -> "video/mp4"
            displayName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            else -> ""
        }
    }

    private fun pruneStagedFiles() {
        val dir = File(cacheDir, "shared-media")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - (2L * 24L * 60L * 60L * 1000L)
        dir.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
