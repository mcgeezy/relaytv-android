package pro.relaytv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

class ShareWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    companion object {
        const val KEY_BASE = "base"
        const val KEY_URL = "url"
        private const val CHANNEL_ID = "relaytv_silent"
        private const val NOTIF_ID = 4242
    }

    override fun doWork(): Result {
        val base = inputData.getString(KEY_BASE)?.trim()?.trimEnd('/') ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        return try {
            val payload = JSONObject().put("url", url).toString()
            val req = Net.postJson(base + "/smart", payload)

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

                postNotification(msg, url, tapToOpen = true)
                if (ok) Result.success() else Result.retry()
            }
        } catch (e: Exception) {
            val reason = (e.message ?: e.javaClass.simpleName).take(80)
            if (reason.contains("timeout", ignoreCase = true)) {
                postNotification("Sent (processing)", url, tapToOpen = true)
                Result.success()
            } else {
                postNotification("Send failed: $reason", url, tapToOpen = true)
                Result.retry()
            }
        }
    }

    private fun postNotification(title: String, text: String, tapToOpen: Boolean) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "RelayTV", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }

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
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            builder.setContentIntent(pending)
        }

        nm.notify(NOTIF_ID, builder.build())
    }
}
