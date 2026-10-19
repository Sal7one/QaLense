package com.qalens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * QaLens overlay design tokens.
 *
 * WHY THIS EXISTS: the overlay used to hard-code its colours per file — the bubble was
 * `#111827`, the panel `#E6111827`, the Control Room `#0B0F17` — so the floating layer
 * looked pasted onto every host app and no integration could influence it.
 *
 * There is exactly ONE decision here: is the host app light or dark? Everything else is
 * derived. A light host gets a dark overlay panel (the ink the product already used); a
 * dark host gets a light overlay panel. In both cases the floating layer separates from
 * the app by contrast rather than by a drop shadow, which is what stopped it reading as a
 * sticker on the cream sample app.
 *
 * CONTRAST: every colour below was measured, not estimated, against the surface it is
 * actually used on. The comment on each line carries the worst-case ratio, and all of them
 * clear WCAG AA 4.5:1 for text. If you change a value, recompute — see
 * `docs/OVERLAY_DESIGN.md` for the two tables and how they were produced.
 *
 * The product intent and adoption status live in `docs/OVERLAY_DESIGN.md`; keep this
 * implementation and that guide in step.
 */
@Immutable
data class QaLensOverlayColors(
    val scheme: Scheme,
    /** App canvas behind the overlay. Used for the bubble halo and the scrim edge. */
    val hostBg: Color,
    // Panel surfaces, lowest to highest elevation.
    val panel: Color,
    val panel2: Color,
    val panel3: Color,
    val well: Color,
    // Text: primary, secondary, tertiary.
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    // Structure.
    val border: Color,
    val borderStrong: Color,
    val grid: Color,
    val scrim: Color,
    val canvasWash: Color,
    // Signal scale — semantic, never decorative.
    val accent: Color,
    val ok: Color,
    val info: Color,
    val warn: Color,
    val err: Color,
    /** Fill that carries white text (record / stop buttons). */
    val critSolid: Color,
    /** Tinted backgrounds for chips and tiles. */
    val accentWash: Color,
    val okWash: Color,
    val infoWash: Color,
    val warnWash: Color,
    val errWash: Color,
) {
    enum class Scheme { LIGHT_HOST, DARK_HOST }

    companion object {
        /**
         * A light host against a dark overlay.
         *
         * `panel` is the ink the product already used (`#111827`); the neutrals are one
         * family now instead of eight ad-hoc values, and the signals are primed for a dark
         * ground rather than reused from a light one. That last point was a real defect in
         * the first pass of this design: the signal colours measured 2.2–3.8:1 here.
         */
        val lightHost = QaLensOverlayColors(
            scheme = Scheme.LIGHT_HOST,
            hostBg = Color(0xFFF6F1E7),
            panel = Color(0xFF111827),
            panel2 = Color(0xFF171F29),
            panel3 = Color(0xFF232B37),
            well = Color(0xFF0A0F17),
            fg = Color(0xFFEEF2F6),   // 16.6:1 on panel
            fg2 = Color(0xFFAEB9C6),  //  9.4:1 on panel
            fg3 = Color(0xFF7D8898),  //  5.2:1 on panel, 4.6:1 on panel2
            border = Color(0x1FFFFFFF),
            borderStrong = Color(0x33FFFFFF),
            grid = Color(0x14FFFFFF),
            scrim = Color(0x8C080B10),
            canvasWash = Color(0x0A080B10),
            accent = Color(0xFF4FC4D0), //  9.0:1 on panel
            ok = Color(0xFF57BF7E),     //  8.1:1
            info = Color(0xFF6BA8E8),   //  7.5:1
            warn = Color(0xFFE0A93C),   //  8.8:1
            err = Color(0xFFE87575),    //  6.4:1
            critSolid = Color(0xFF8E1B1B),
            accentWash = Color(0x244FC4D0),
            okWash = Color(0x2457BF7E),
            infoWash = Color(0x246BA8E8),
            warnWash = Color(0x24E0A93C),
            errWash = Color(0x24E87575),
        )

        /**
         * A dark host against a light overlay.
         *
         * The light panel is opaque, not translucent: over a dark app a translucent panel
         * has almost no contrast, so the overlay would disappear at exactly the moment it
         * matters. Deliberately the same component structure — only the ramp flips.
         */
        val darkHost = QaLensOverlayColors(
            scheme = Scheme.DARK_HOST,
            hostBg = Color(0xFF10161E),
            panel = Color(0xFFFFFFFF),
            panel2 = Color(0xFFF1F4F8),
            panel3 = Color(0xFFE4E9F0),
            well = Color(0xFFF7F9FC),
            fg = Color(0xFF14161A),   // 18.1:1 on panel
            fg2 = Color(0xFF4A5462),  //  7.7:1 on panel
            fg3 = Color(0xFF5F6A7A),  //  5.5:1 on panel, 5.0:1 on panel2
            border = Color(0x14000000),
            borderStrong = Color(0x2E000000),
            grid = Color(0x0F000000),
            scrim = Color(0x9E000000),
            canvasWash = Color(0x1A000000),
            accent = Color(0xFF0E6F7A), //  5.9:1 on panel, 5.3:1 on panel2
            ok = Color(0xFF12703B),     //  6.2:1
            info = Color(0xFF1B5AA0),   //  7.0:1
            warn = Color(0xFF8A5300),   //  6.3:1
            err = Color(0xFFB02020),    //  6.8:1
            critSolid = Color(0xFF8E1B1B),
            accentWash = Color(0x1F0E6F7A),
            okWash = Color(0x1F12703B),
            infoWash = Color(0x1F1B5AA0),
            warnWash = Color(0x1F8A5300),
            errWash = Color(0x1FB02020),
        )

        /**
         * Pick the overlay palette for a host background.
         *
         * The threshold is on perceived luminance, not on `isSystemInDarkTheme()`: an app
         * can ship a dark theme while the system stays light, or render a dark canvas in a
         * light theme. What matters is the surface the overlay actually floats over.
         */
        fun forHost(hostBackground: Color): QaLensOverlayColors =
            if (hostBackground.luminance() > 0.5f) lightHost else darkHost

        private fun Color.luminance(): Float {
            fun channel(c: Float): Float =
                if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).let { it * it * it * it * it }
            return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
        }
    }
}

/**
 * Resolve the overlay palette from the device/app configuration.
 *
 * The overlay is attached as a decor-level ComposeView, so it sits OUTSIDE the host's
 * Material theme and cannot read `MaterialTheme.colorScheme`. The configuration's night
 * mode is the honest signal available at that layer, and it is what the host app itself
 * follows when it flips themes.
 */
fun qaLensColorsFor(context: android.content.Context): QaLensOverlayColors =
    qaLensColorsForScheme(schemeForConfiguration(context.resources.configuration))

/**
 * The decision itself, split out so it can be unit-tested without an Android Context.
 */
fun schemeForConfiguration(
    configuration: android.content.res.Configuration
): QaLensOverlayColors.Scheme {
    val nightMask = configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
        QaLensOverlayColors.Scheme.DARK_HOST
    } else {
        QaLensOverlayColors.Scheme.LIGHT_HOST
    }
}

/** Map a scheme to its palette. */
fun qaLensColorsForScheme(scheme: QaLensOverlayColors.Scheme): QaLensOverlayColors =
    when (scheme) {
        QaLensOverlayColors.Scheme.LIGHT_HOST -> QaLensOverlayColors.lightHost
        QaLensOverlayColors.Scheme.DARK_HOST -> QaLensOverlayColors.darkHost
    }

/** Sizing tokens. Named by role so a surface never invents its own number. */
object QaLensDimens {    /** Android minimum touch target. Every interactive element must clear this. */
    val touchMin: Dp = 48.dp

    // 4dp base scale.
    val s1: Dp = 4.dp
    val s2: Dp = 8.dp
    val s3: Dp = 12.dp
    val s4: Dp = 16.dp
    val s5: Dp = 20.dp
    val s6: Dp = 24.dp

    // Radii — one family. Panels generous, controls tight.
    val rChip: Dp = 999.dp
    val rXs: Dp = 6.dp
    val rSm: Dp = 10.dp
    val rMd: Dp = 14.dp
    val rXl: Dp = 24.dp

    /** The QA bubble. */
    val bubble: Dp = 58.dp
    val bubbleBorder: Dp = 2.dp
    val bubbleHalo: Dp = 2.dp

    /** The tester quick-actions sheet, sized to fit small phones. */
    val sheetWidth: Dp = 340.dp
    val sheetRadius: Dp = 22.dp

    /** Fixed leading column for timestamped rows, so values line up down a track. */
    val rowTimeColumn: Dp = 46.dp
}

/** Motion tokens. Zero under a reduced-motion preference; see [QaLensMotion]. */
object QaLensMotion {
    const val FAST_MS = 120
    const val SHORT_MS = 180
}

/**
 * Type scale. Numerals that are compared or scanned use the monospace family with
 * tabular figures — see `QaLensNumericStyle` on the overlay side.
 */
object QaLensType {
    val t9 = 9.sp
    val t10 = 10.sp
    val t11 = 11.sp
    val t12 = 12.sp
    val t13 = 13.sp
    val t14 = 14.sp
    val t16 = 16.sp
    val t20 = 20.sp
    val t26 = 26.sp
    val t34 = 34.sp

    /** Uppercase micro-label tracking. */
    const val CAPS_TRACKING = 0.07f
}
