package pro.relaytv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val appBar = findViewById<AppBarLayout>(R.id.appBar)
        applyWindowInsets(appBar)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        toolbar.setNavigationOnClickListener { finish() }

        val mediaSwitch = findViewById<MaterialSwitch>(R.id.switchMediaControls)
        mediaSwitch.isChecked = AppSettings.isMediaControlsEnabled(this)
        mediaSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setMediaControlsEnabled(this, checked)
            val svc = Intent(this, MediaControlService::class.java)
            if (checked) {
                runCatching { startService(svc) }
            } else {
                stopService(svc)
            }
        }
        findViewById<LinearLayout>(R.id.rowMediaControls).setOnClickListener {
            mediaSwitch.toggle()
        }

        findViewById<LinearLayout>(R.id.rowManageServers).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra("open_servers", true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
    }

    private fun applyWindowInsets(appBar: AppBarLayout) {
        val initialTop = appBar.paddingTop
        val initialLeft = appBar.paddingLeft
        val initialRight = appBar.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view: View, insets: WindowInsetsCompat ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                view.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(appBar)
    }
}
