package com.qalens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two claims the overlay design makes about itself, so a future colour edit
 * cannot quietly break either one:
 *
 *  1. the host-adaptive choice picks the right palette, and
 *  2. every signal and text colour actually clears WCAG AA on the surface it is used on.
 *
 * The second one matters because an early draft of this design quoted contrast ratios that
 * were never computed: the signal colours measured 2.2–3.8:1 against the dark panel they
 * were drawn on, because they had been chosen against a light surface. Tests like these are
 * how that stops happening again.
 */
class QaLensTokensTest {

    // WCAG 2.x relative luminance, on the 0..1 sRGB channels Compose already gives us.
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private val AA = 4.5

    @Test
    fun `a light host yields the dark overlay palette`() {
        // The bundled sample app is cream, so this is the path the demo actually takes.
        assertEquals(
            QaLensOverlayColors.Scheme.LIGHT_HOST,
            QaLensOverlayColors.forHost(Color(0xFFF6F1E7)).scheme,
        )
        assertEquals(
            QaLensOverlayColors.Scheme.LIGHT_HOST,
            QaLensOverlayColors.forHost(Color.White).scheme,
        )
    }

    @Test
    fun `a dark host yields the light overlay palette`() {
        assertEquals(
            QaLensOverlayColors.Scheme.DARK_HOST,
            QaLensOverlayColors.forHost(Color(0xFF10161E)).scheme,
        )
        assertEquals(
            QaLensOverlayColors.Scheme.DARK_HOST,
            QaLensOverlayColors.forHost(Color.Black).scheme,
        )
    }

    @Test
    fun `text colours clear AA on their panel surfaces`() {
        listOf(QaLensOverlayColors.lightHost, QaLensOverlayColors.darkHost).forEach { c ->
            assertTrue(
                "fg on panel for ${c.scheme}: ${contrast(c.fg, c.panel)}",
                contrast(c.fg, c.panel) >= AA,
            )
            assertTrue(
                "fg2 on panel for ${c.scheme}: ${contrast(c.fg2, c.panel)}",
                contrast(c.fg2, c.panel) >= AA,
            )
            assertTrue(
                "fg3 on panel for ${c.scheme}: ${contrast(c.fg3, c.panel)}",
                contrast(c.fg3, c.panel) >= AA,
            )
            assertTrue(
                "fg3 on panel2 for ${c.scheme}: ${contrast(c.fg3, c.panel2)}",
                contrast(c.fg3, c.panel2) >= AA,
            )
        }
    }

    @Test
    fun `every signal colour clears AA on both panel surfaces`() {
        listOf(QaLensOverlayColors.lightHost, QaLensOverlayColors.darkHost).forEach { c ->
            val signals = mapOf(
                "accent" to c.accent,
                "ok" to c.ok,
                "info" to c.info,
                "warn" to c.warn,
                "err" to c.err,
            )
            signals.forEach { (name, color) ->
                assertTrue(
                    "$name on panel for ${c.scheme}: ${contrast(color, c.panel)}",
                    contrast(color, c.panel) >= AA,
                )
                assertTrue(
                    "$name on panel2 for ${c.scheme}: ${contrast(color, c.panel2)}",
                    contrast(color, c.panel2) >= AA,
                )
            }
        }
    }

    @Test
    fun `white text on the critical fill clears AA`() {
        assertTrue(contrast(Color.White, QaLensOverlayColors.lightHost.critSolid) >= AA)
        assertTrue(contrast(Color.White, QaLensOverlayColors.darkHost.critSolid) >= AA)
    }

    @Test
    fun `the two schemes are genuinely different`() {
        // Guards against someone collapsing the adaptive branch to a single palette.
        assertTrue(QaLensOverlayColors.lightHost.panel != QaLensOverlayColors.darkHost.panel)
        assertTrue(QaLensOverlayColors.lightHost.fg != QaLensOverlayColors.darkHost.fg)
    }

    @Test
    fun `touch minimum is the Android 48dp floor`() {
        assertEquals(48f, QaLensDimens.touchMin.value, 0.001f)
    }

    @Test
    fun `context resolution follows the night-mode mask`() {
        // The overlay attaches at decor level, outside the host Material theme, so the
        // configuration mask is the only honest signal it has. Verify both branches.
        val light = android.content.res.Configuration().apply {
            uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        val dark = android.content.res.Configuration().apply {
            uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        assertEquals(QaLensOverlayColors.Scheme.LIGHT_HOST, schemeForConfiguration(light))
        assertEquals(QaLensOverlayColors.Scheme.DARK_HOST, schemeForConfiguration(dark))

        // ...and the scheme maps to the expected palette.
        assertEquals(
            QaLensOverlayColors.lightHost.panel,
            qaLensColorsForScheme(schemeForConfiguration(light)).panel,
        )
        assertEquals(
            QaLensOverlayColors.darkHost.panel,
            qaLensColorsForScheme(schemeForConfiguration(dark)).panel,
        )
    }
}
