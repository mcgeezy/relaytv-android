package pro.relaytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBridgePolicyTest {

    @Test
    fun trustedOriginMatchesSameServerAndPath() {
        assertTrue(
            WebBridgePolicy.isTrustedBridgeOrigin(
                activeBaseUrl = "http://10.0.55.2:8787",
                currentPageUrl = "http://10.0.55.2:8787/ui",
            )
        )
        assertTrue(
            WebBridgePolicy.isTrustedBridgeOrigin(
                activeBaseUrl = "https://tv.example.com/relay",
                currentPageUrl = "https://tv.example.com/relay/ui?tab=queue",
            )
        )
    }

    @Test
    fun trustedOriginRejectsDifferentHostPortSchemeOrBlank() {
        assertFalse(
            WebBridgePolicy.isTrustedBridgeOrigin(
                activeBaseUrl = "http://10.0.55.2:8787",
                currentPageUrl = "http://10.0.55.3:8787/ui",
            )
        )
        assertFalse(
            WebBridgePolicy.isTrustedBridgeOrigin(
                activeBaseUrl = "http://10.0.55.2:8787",
                currentPageUrl = "http://10.0.55.2:9999/ui",
            )
        )
        assertFalse(
            WebBridgePolicy.isTrustedBridgeOrigin(
                activeBaseUrl = "https://tv.example.com",
                currentPageUrl = "http://tv.example.com/ui",
            )
        )
        assertFalse(WebBridgePolicy.isTrustedBridgeOrigin(null, "http://10.0.55.2:8787/ui"))
        assertFalse(WebBridgePolicy.isTrustedBridgeOrigin("http://10.0.55.2:8787", ""))
        assertFalse(WebBridgePolicy.isTrustedBridgeOrigin("http://10.0.55.2:8787", "about:blank"))
    }

    @Test
    fun parseHexColorHandlesSixAndEightDigitHex() {
        assertEquals(0xFF05070D.toInt(), WebBridgePolicy.parseHexColor("#05070d"))
        assertEquals(0xFFEDF2FF.toInt(), WebBridgePolicy.parseHexColor("  #EDF2FF  "))
        assertEquals(0x80112233.toInt(), WebBridgePolicy.parseHexColor("#80112233"))
        assertNull(WebBridgePolicy.parseHexColor("#fff"))
        assertNull(WebBridgePolicy.parseHexColor("05070d"))
        assertNull(WebBridgePolicy.parseHexColor("#zzzzzz"))
        assertNull(WebBridgePolicy.parseHexColor(null))
    }

    @Test
    fun resolveThemeSyncUsesProvidedHexOrSensibleDefault() {
        val light = WebBridgePolicy.resolveThemeSync("light", "#edf2ff")
        assertTrue(light.isLight)
        assertEquals(0xFFEDF2FF.toInt(), light.backgroundColor)

        val dark = WebBridgePolicy.resolveThemeSync("dark", "#05070d")
        assertFalse(dark.isLight)
        assertEquals(0xFF05070D.toInt(), dark.backgroundColor)

        val fallbackLight = WebBridgePolicy.resolveThemeSync("LIGHT", "not-a-color")
        assertTrue(fallbackLight.isLight)
        assertEquals(WebBridgePolicy.DEFAULT_LIGHT_BG, fallbackLight.backgroundColor)

        val fallbackDark = WebBridgePolicy.resolveThemeSync("unknown", null)
        assertFalse(fallbackDark.isLight)
        assertEquals(WebBridgePolicy.DEFAULT_DARK_BG, fallbackDark.backgroundColor)
    }

    @Test
    fun fallbackPillStateTransitions() {
        assertEquals(
            FallbackPillState.HIDDEN,
            WebBridgePolicy.resolveFallbackPillState(
                bridgeReady = false,
                isOffline = false,
                legacyGraceElapsed = false,
            )
        )
        assertEquals(
            FallbackPillState.HIDDEN,
            WebBridgePolicy.resolveFallbackPillState(
                bridgeReady = true,
                isOffline = false,
                legacyGraceElapsed = true,
            )
        )
        assertEquals(
            FallbackPillState.LEGACY_CONNECTED,
            WebBridgePolicy.resolveFallbackPillState(
                bridgeReady = false,
                isOffline = false,
                legacyGraceElapsed = true,
            )
        )
        assertEquals(
            FallbackPillState.OFFLINE,
            WebBridgePolicy.resolveFallbackPillState(
                bridgeReady = false,
                isOffline = true,
                legacyGraceElapsed = false,
            )
        )
        assertEquals(
            FallbackPillState.OFFLINE,
            WebBridgePolicy.resolveFallbackPillState(
                bridgeReady = true,
                isOffline = true,
                legacyGraceElapsed = false,
            )
        )
    }
}
