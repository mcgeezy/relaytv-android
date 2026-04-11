package pro.relaytv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File

class ShareWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    private class ShareFailure(
        message: String,
        val retryable: Boolean,
    ) : Exception(message)

    companion object {
        private const val TAG = "RelayTVShare"
        const val KEY_BASE = "base"
        const val KEY_URL = "url"
        const val KEY_ENDPOINT_PATH = "endpoint_path"
        const val KEY_UPLOAD_FILE_PATH = "upload_file_path"
        const val KEY_UPLOAD_MIME_TYPE = "upload_mime_type"
        const val KEY_UPLOAD_TITLE = "upload_title"
        private const val CHANNEL_ID = "relaytv_silent"
        private const val NOTIF_ID = 4242
    }

    override fun doWork(): Result {
        val base = inputData.getString(KEY_BASE)?.trim()?.trimEnd('/') ?: return Result.failure()
        val endpointPath = inputData.getString(KEY_ENDPOINT_PATH)?.trim()?.ifBlank { "/smart" } ?: "/smart"
        val uploadPath = inputData.getString(KEY_UPLOAD_FILE_PATH)?.trim().orEmpty()
        val localUpload = uploadPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val displayText = inputData.getString(KEY_UPLOAD_TITLE)?.takeIf { !it.isNullOrBlank() }
            ?: localUpload?.name
            ?: inputData.getString(KEY_URL)
            ?: "item"

        return try {
            if (localUpload != null && endpointPath == "/play_now") {
                val playResult = uploadAndPlayMedia(base, localUpload)
                postNotification(playResult.title, displayText, tapToOpen = true)
                localUpload.delete()
                return Result.success()
            }

            if (localUpload != null) {
                val enqueueResult = uploadAndEnqueueMedia(base, localUpload)
                postNotification(enqueueResult.title, displayText, tapToOpen = true)
                localUpload.delete()
                return Result.success()
            }

            val url = inputData.getString(KEY_URL) ?: return Result.failure()

            val payload = JSONObject().put("url", url).toString()
            val req = Net.postJson(base + endpointPath, payload)

            Net.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val ok = resp.isSuccessful
                val msg = runCatching {
                    val j = JSONObject(body)
                    when (j.optString("status")) {
                        "playing" -> "Playing now"
                        "queued" -> "Enqueued"
                        else -> if (ok) "Sent" else "Error"
                    }
                }.getOrDefault(if (ok) "Sent" else "Error")

                postNotification(msg, displayText, tapToOpen = true)
                when {
                    ok -> {
                        localUpload?.delete()
                        Result.success()
                    }
                    shouldRetry(resp.code) -> Result.retry()
                    else -> {
                        localUpload?.delete()
                        Result.failure()
                    }
                }
            }
        } catch (e: ShareFailure) {
            postNotification("Send failed: ${e.message.orEmpty().take(80)}", displayText, tapToOpen = true)
            if (!e.retryable) {
                localUpload?.delete()
            }
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: Exception) {
            val reason = (e.message ?: e.javaClass.simpleName).take(80)
            if (reason.contains("timeout", ignoreCase = true)) {
                postNotification("Sent (processing)", displayText, tapToOpen = true)
                Result.success()
            } else {
                postNotification("Send failed: $reason", displayText, tapToOpen = true)
                Result.retry()
            }
        }
    }

    private data class UploadPlayResult(
        val title: String,
        val playbackMode: String,
    )

    private data class UploadEnqueueResult(
        val title: String,
    )

    private fun uploadAndEnqueueMedia(base: String, file: File): UploadEnqueueResult {
        if (!file.exists() || !file.isFile) {
            throw ShareFailure("Shared media file is no longer available", retryable = false)
        }
        val mimeType = inputData.getString(KEY_UPLOAD_MIME_TYPE)?.trim().orEmpty()
        val title = inputData.getString(KEY_UPLOAD_TITLE)?.trim().orEmpty()
        Log.i(TAG, "uploadAndEnqueueMedia file=${file.name} size=${file.length()} mime=$mimeType title=$title")
        val req = Net.postMultipartFile(
            url = "$base/ingest/media/enqueue",
            file = file,
            mimeType = mimeType.ifBlank { null },
            title = title.ifBlank { null },
        )
        Net.uploadClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "uploadAndEnqueueMedia failed code=${resp.code} body=${body.take(300)}")
                val detail = extractDetail(body) ?: "Upload enqueue failed"
                throw ShareFailure(detail, retryable = shouldRetry(resp.code))
            }
            Log.i(TAG, "uploadAndEnqueueMedia success code=${resp.code} body=${body.take(300)}")
            val json = JSONObject(body)
            val result = json.optJSONObject("result")
            val titleText = when (result?.optString("status")) {
                "queued" -> "Enqueued"
                else -> "Enqueued"
            }
            return UploadEnqueueResult(title = titleText)
        }
    }

    private fun uploadAndPlayMedia(base: String, file: File): UploadPlayResult {
        if (!file.exists() || !file.isFile) {
            throw ShareFailure("Shared media file is no longer available", retryable = false)
        }
        val mimeType = inputData.getString(KEY_UPLOAD_MIME_TYPE)?.trim().orEmpty()
        val title = inputData.getString(KEY_UPLOAD_TITLE)?.trim().orEmpty()
        Log.i(TAG, "uploadAndPlayMedia file=${file.name} size=${file.length()} mime=$mimeType title=$title")
        val req = Net.postMultipartFile(
            url = "$base/ingest/media/play",
            file = file,
            mimeType = mimeType.ifBlank { null },
            title = title.ifBlank { null },
        )
        Net.uploadClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "uploadAndPlayMedia failed code=${resp.code} body=${body.take(300)}")
                val detail = extractDetail(body) ?: "Upload playback failed"
                throw ShareFailure(detail, retryable = shouldRetry(resp.code))
            }
            Log.i(TAG, "uploadAndPlayMedia success code=${resp.code} body=${body.take(300)}")
            val json = JSONObject(body)
            val playbackMode = json.optString("playback_mode").trim()
            val message = when (playbackMode) {
                "progressive" -> "Playing now"
                "full_upload" -> "Playing now"
                else -> "Playing now"
            }
            return UploadPlayResult(
                title = message,
                playbackMode = playbackMode,
            )
        }
    }

    private fun extractDetail(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            json.optString("detail").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun shouldRetry(code: Int): Boolean {
        return code == 408 || code == 429 || code >= 500
    }

    private fun postNotification(title: String, text: String, tapToOpen: Boolean) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ch = NotificationChannel(CHANNEL_ID, "RelayTV", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text.take(90))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (tapToOpen) {
            val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                applicationContext,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pending)
        }

        nm.notify(NOTIF_ID, builder.build())
    }
}
