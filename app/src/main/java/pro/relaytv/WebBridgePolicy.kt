package pro.relaytv

internal enum class FallbackPillState {
    HIDDEN,
    LEGACY_CONNECTED,
    OFFLINE,
}

internal data class ThemeSyncState(
    val isLight: Boolean,
    val backgroundColor: Int,
)

internal object WebBridgePolicy {
    const val DEFAULT_DARK_BG: Int = 0xFF05070D.toInt()
    const val DEFAULT_LIGHT_BG: Int = 0xFFEDF2FF.toInt()
    const val LEGACY_FALLBACK_DELAY_MS: Long = 900L

    fun isTrustedBridgeOrigin(activeBaseUrl: String?, currentPageUrl: String?): Boolean {
        if (activeBaseUrl.isNullOrBlank() || currentPageUrl.isNullOrBlank()) {
            return false
        }
        return HostStore.isSameServerUrl(activeBaseUrl, currentPageUrl)
    }

    fun parseHexColor(hex: String?): Int? {
        val raw = hex?.trim() ?: return null
        if (!raw.startsWith("#")) return null
        val digits = raw.substring(1)
        return when (digits.length) {
            6 -> {
                val rgb = digits.toLongOrNull(16) ?: return null
                (0xFF000000L or rgb).toInt()
            }
            8 -> {
                val argb = digits.toLongOrNull(16) ?: return null
                argb.toInt()
            }
            else -> null
        }
    }

    fun resolveThemeSync(effectiveTheme: String?, bgColorHex: String?): ThemeSyncState {
        val isLight = effectiveTheme?.trim()?.equals("light", ignoreCase = true) == true
        val fallbackColor = if (isLight) DEFAULT_LIGHT_BG else DEFAULT_DARK_BG
        val resolvedColor = parseHexColor(bgColorHex) ?: fallbackColor
        return ThemeSyncState(
            isLight = isLight,
            backgroundColor = resolvedColor,
        )
    }

    fun resolveFallbackPillState(
        bridgeReady: Boolean,
        isOffline: Boolean,
        legacyGraceElapsed: Boolean,
    ): FallbackPillState {
        return when {
            isOffline -> FallbackPillState.OFFLINE
            bridgeReady -> FallbackPillState.HIDDEN
            legacyGraceElapsed -> FallbackPillState.LEGACY_CONNECTED
            else -> FallbackPillState.HIDDEN
        }
    }
}
