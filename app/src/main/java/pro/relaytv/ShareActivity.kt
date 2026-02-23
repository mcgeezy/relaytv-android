package pro.relaytv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ShareActivity : AppCompatActivity() {

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

        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = extractUrl(shared) ?: run { finish(); return }

        val input = Data.Builder()
            .putString(ShareWorker.KEY_BASE, base)
            .putString(ShareWorker.KEY_URL, url)
            .build()

        val req = OneTimeWorkRequestBuilder<ShareWorker>()
            .setInputData(input)
            .build()

        WorkManager.getInstance(this).enqueue(req)
        val serverName = HostStore.getActiveHost(this)?.name?.ifBlank { "Server" } ?: "Server"
        Toast.makeText(this, "Sent to \"$serverName\"", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }

    private fun extractUrl(text: String): String? {
        val m = Regex("""https?://\S+""").find(text)
        return m?.value?.trim()?.trimEnd(')', ']', '>', '"', '\'')
    }
}
