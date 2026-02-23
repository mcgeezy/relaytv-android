package pro.relaytv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Kept for backward compatibility (older deep links / flows).
 * This activity simply opens the server picker inside MainActivity.
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(android.content.Intent(this, MainActivity::class.java).apply {
            putExtra("open_servers", true)
        })
        finish()
    }
}
