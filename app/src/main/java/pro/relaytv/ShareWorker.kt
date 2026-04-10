package pro.relaytv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
            val url = when {
                localUpload != null -> uploadMedia(base, localUpload)
                else -> inputData.getString(KEY_URL) ?: return Result.failure()
            }

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

    private fun uploadMedia(base: String, file: File): String {
        if (!file.exists() || !file.isFile) {
            throw ShareFailure("Shared media file is no longer available", retryable = false)
        }
        val mimeType = inputData.getString(KEY_UPLOAD_MIME_TYPE)?.trim().orEmpty()
        val title = inputData.getString(KEY_UPLOAD_TITLE)?.trim().orEmpty()
        val req = Net.postMultipartFile(
            url = "$base/ingest/media",
            file = file,
            mimeType = mimeType.ifBlank { null },
            title = title.ifBlank { null },
        )
        Net.uploadClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractDetail(body) ?: "Upload failed"
                throw ShareFailure(detail, retryable = shouldRetry(resp.code))
            }
            val json = JSONObject(body)
            val uploadedUrl = json.optString("url").trim()
            if (uploadedUrl.isBlank()) {
                throw ShareFailure("Upload completed without a playable URL", retryable = false)
            }
            return uploadedUrl
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
