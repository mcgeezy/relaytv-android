package pro.relaytv

import android.content.Context

/** App-level feature settings (separate from the server list in [HostStore]). */
object AppSettings {
    private const val PREF = "relaytv_prefs"
    private const val KEY_MEDIA_CONTROLS = "media_controls_enabled"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isMediaControlsEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MEDIA_CONTROLS, true)

    fun setMediaControlsEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MEDIA_CONTROLS, enabled).apply()
    }
}
