package pro.relaytv

import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ShareActivity : AppCompatActivity() {

    companion object {
        private const val META_ENDPOINT_PATH = "pro.relaytv.SHARE_ENDPOINT_PATH"
        private const val META_SUCCESS_TEMPLATE = "pro.relaytv.SHARE_SUCCESS_TEMPLATE"
    }

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
        val endpointPath = resolveMetaString(META_ENDPOINT_PATH).ifBlank { "/smart" }
        val successTemplate = resolveMetaString(META_SUCCESS_TEMPLATE).ifBlank { "Sent to \"%s\"" }

        val input = Data.Builder()
            .putString(ShareWorker.KEY_BASE, base)
            .putString(ShareWorker.KEY_URL, url)
            .putString(ShareWorker.KEY_ENDPOINT_PATH, endpointPath)
            .build()

        val req = OneTimeWorkRequestBuilder<ShareWorker>()
            .setInputData(input)
            .build()

        WorkManager.getInstance(this).enqueue(req)
        val serverName = HostStore.getActiveHost(this)?.name?.ifBlank { "Server" } ?: "Server"
        Toast.makeText(this, successTemplate.format(serverName), Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
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
}
