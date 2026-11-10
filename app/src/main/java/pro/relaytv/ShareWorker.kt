package pro.relaytv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
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
        private const val MAX_RETRY_ATTEMPTS = 3
        private val FAST_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L)

        internal enum class ExceptionAction {
            SUCCESS_PROCESSING,
            RETRY,
            FAILURE,
        }

        internal fun canRetryAttempt(runAttemptCount: Int): Boolean =
            runAttemptCount < MAX_RETRY_ATTEMPTS

        internal fun isTimeoutError(error: Throwable): Boolean {
            if (error is java.net.SocketTimeoutException || error is java.io.InterruptedIOException) {
                return true
            }
            return (error.message ?: "").contains("timeout", ignoreCase = true)
        }

        internal fun isTransientConnectionError(error: Throwable): Boolean {
            if (isTimeoutError(error)) return false
            return error is java.net.ConnectException ||
                error is java.net.UnknownHostException ||
                error is java.net.NoRouteToHostException ||
                error is java.net.SocketException
        }

        internal fun resolveExceptionAction(
            isLocalUpload: Boolean,
            isTimeout: Boolean,
            runAttemptCount: Int,
        ): ExceptionAction = when {
            !isLocalUpload && isTimeout -> ExceptionAction.SUCCESS_PROCESSING
            canRetryAttempt(runAttemptCount) -> ExceptionAction.RETRY
            else -> ExceptionAction.FAILURE
        }

        internal fun extractDetail(body: String): String? {
            if (body.isBlank()) return null
            return runCatching {
                val json = JSONObject(body)
                json.optString("detail").takeIf { it.isNotBlank() }
                    ?: json.optString("error").takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        internal fun parseShareResponseMessage(code: Int, ok: Boolean, body: String): String {
            if (code == 401) return "API token required"
            if (!ok) {
                val detail = extractDetail(body)
                return if (!detail.isNullOrBlank()) "Send failed: ${detail.take(80)}" else "Error"
            }
            return runCatching {
                val j = JSONObject(body)
                val status = j.optString("status").trim()
                val action = j.optString("action").trim()
                val effectiveAction = j.optString("effective_action").trim()
                val detail = j.optString("detail").trim()
                when {
                    status == "playing" || action == "played" -> "Playing now"
                    status == "queued" -> "Enqueued"
                    status == "loading" || status == "adding" -> {
                        detail.ifBlank {
                            if (effectiveAction == "play_now") "Importing playlist to play…" else "Importing playlist…"
                        }
                    }
                    status == "completed" -> {
                        detail.ifBlank {
                            if (effectiveAction == "play_now") "Playing now" else "Enqueued"
                        }
                    }
                    else -> "Sent"
                }
            }.getOrDefault("Sent")
        }
    }

    override fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(nm)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Sending to RelayTV…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(id.hashCode(), notification)
    }

    override fun doWork(): Result {
        val base = inputData.getString(KEY_BASE)?.trim()?.trimEnd('/') ?: return Result.failure()
        val endpointPath = inputData.getString(KEY_ENDPOINT_PATH)?.trim()?.ifBlank { "/smart" } ?: "/smart"
        val apiToken = HostStore.getApiTokenForBase(applicationContext, base)
        val uploadPath = inputData.getString(KEY_UPLOAD_FILE_PATH)?.trim().orEmpty()
        val localUpload = uploadPath.takeIf { it.isNotBlank() }?.let { File(it) }
        val displayText = inputData.getString(KEY_UPLOAD_TITLE)?.takeIf { !it.isNullOrBlank() }
            ?: localUpload?.name
            ?: inputData.getString(KEY_URL)
            ?: "item"

        return try {
            if (localUpload != null && endpointPath == "/play_now") {
                val playResult = executeWithFastRetry {
                    uploadAndPlayMedia(base, localUpload, apiToken)
                }
                ensureMediaControlService()
                postNotification(playResult.title, displayText, tapToOpen = true)
                localUpload.delete()
                return Result.success()
            }

            if (localUpload != null) {
                val enqueueResult = executeWithFastRetry {
                    uploadAndEnqueueMedia(base, localUpload, apiToken)
                }
                ensureMediaControlService()
                postNotification(enqueueResult.title, displayText, tapToOpen = true)
                localUpload.delete()
                return Result.success()
            }

            val url = inputData.getString(KEY_URL) ?: return Result.failure()

            val payload = JSONObject()
                .put("url", url)
                .put("request_id", id.toString())
                .toString()

            val (code, ok, body) = executeWithFastRetry {
                val req = Net.postJson(base + endpointPath, payload, apiToken)
                Net.client.newCall(req).execute().use { resp ->
                    Triple(resp.code, resp.isSuccessful, resp.body?.string().orEmpty())
                }
            }
            val msg = parseShareResponseMessage(code, ok, body)

            when {
                ok -> {
                    localUpload?.delete()
                    ensureMediaControlService()
                    postNotification(msg, displayText, tapToOpen = true)
                    Result.success()
                }
                shouldRetry(code) && canRetryAttempt(runAttemptCount) -> Result.retry()
                else -> {
                    localUpload?.delete()
                    postNotification(msg, displayText, tapToOpen = true)
                    Result.failure()
                }
            }
        } catch (e: ShareFailure) {
            val canRetry = e.retryable && canRetryAttempt(runAttemptCount)
            if (!canRetry) {
                localUpload?.delete()
                postNotification("Send failed: ${e.message.orEmpty().take(80)}", displayText, tapToOpen = true)
            }
            if (canRetry) Result.retry() else Result.failure()
        } catch (e: Exception) {
            val reason = (e.message ?: e.javaClass.simpleName).take(80)
            when (
                resolveExceptionAction(
                    isLocalUpload = localUpload != null,
                    isTimeout = isTimeoutError(e),
                    runAttemptCount = runAttemptCount,
                )
            ) {
                ExceptionAction.SUCCESS_PROCESSING -> {
                    ensureMediaControlService()
                    postNotification("Sent (processing)", displayText, tapToOpen = true)
                    Result.success()
                }
                ExceptionAction.RETRY -> {
                    Result.retry()
                }
                ExceptionAction.FAILURE -> {
                    localUpload?.delete()
                    postNotification("Send failed: $reason", displayText, tapToOpen = true)
                    Result.failure()
                }
            }
        }
    }

    /**
     * Retries immediate socket/route failures in-worker to bridge short Wi-Fi or VPN wake-from-sleep
     * handshakes without waiting 10–30s for JobScheduler backoff.
     */
    private fun <T> executeWithFastRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (isStopped || !isTransientConnectionError(e) || attempt >= FAST_RETRY_DELAYS_MS.size) {
                    throw e
                }
                val delayMs = FAST_RETRY_DELAYS_MS[attempt]
                attempt += 1
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun ensureMediaControlService() {
        if (!AppSettings.isMediaControlsEnabled(applicationContext)) return
        runCatching {
            applicationContext.startService(Intent(applicationContext, MediaControlService::class.java))
        }
    }

    private data class UploadPlayResult(
        val title: String,
        val playbackMode: String,
    )

    private data class UploadEnqueueResult(
        val title: String,
    )

    private fun uploadAndEnqueueMedia(base: String, file: File, apiToken: String): UploadEnqueueResult {
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
            apiToken = apiToken,
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

    private fun uploadAndPlayMedia(base: String, file: File, apiToken: String): UploadPlayResult {
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
            apiToken = apiToken,
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

    private fun shouldRetry(code: Int): Boolean {
        return code == 408 || code == 429 || code >= 500
    }

    private fun ensureNotificationChannel(nm: NotificationManager) {
        val ch = NotificationChannel(CHANNEL_ID, "RelayTV", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun postNotification(title: String, text: String, tapToOpen: Boolean) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel(nm)

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

        nm.notify(id.hashCode(), builder.build())
    }
}
