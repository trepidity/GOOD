package com.trepidity.good.lcd

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * Colours for one LCD instrument: the [panel], its lit [ink] and faintly visible unlit [ghost] segments,
 * the [caseColor] around it and the [label] printed under case buttons.
 * [isNight] marks palettes that must not show any white UI.
 */
@Immutable
data class LcdPalette(
    val panel: Color,
    val ink: Color,
    val ghost: Color,
    val caseColor: Color,
    val label: Color,
    val isNight: Boolean,
) {
    companion object {
        /** Reflective grey-green panel with near-black segments on a charcoal case. */
        val Day = LcdPalette(
            panel = Color(0xFFA7B39A),
            ink = Color(0xFF1B2116),
            ghost = Color(0xFF1B2116).copy(alpha = 0.08f),
            caseColor = Color(0xFF2B2E2A),
            label = Color(0xFFC4C9BD),
            isNight = false,
        )

        /** GLOW: teal-lit panel with dark segments on a black case. */
        val Night = LcdPalette(
            panel = Color(0xFF2BB5A6),
            ink = Color(0xFF05231F),
            ghost = Color(0xFF05231F).copy(alpha = 0.10f),
            caseColor = Color.Black,
            label = Color(0xFF1C7F74),
            isNight = true,
        )
    }
}

private val SunriseRed = Color(0xFF1A0200)
private val SunriseAmber = Color(0xFFB34A00)
private val SunriseWhite = Color(0xFFFFF2DE)
private val SunriseDarkInk = Color(0xFF1A0800)
private val SunriseDimInk = Color(0xFFA05A2A)

/**
 * The ringing backlight at [progress] (0..1) through the light stage: deep red, then amber, then warm white.
 * Digits are dark once the panel is bright enough to read them against; on the darkest reds they switch to
 * a dim warm ink instead, whichever of the two contrasts more with the panel.
 */
fun LcdPalette.Companion.sunrise(progress: Float): LcdPalette {
    val p = progress.coerceIn(0f, 1f)
    val panel = if (p < 0.5f) lerp(SunriseRed, SunriseAmber, p * 2f) else lerp(SunriseAmber, SunriseWhite, (p - 0.5f) * 2f)
    val ink = if (contrast(SunriseDarkInk, panel) >= contrast(SunriseDimInk, panel)) SunriseDarkInk else SunriseDimInk
    return LcdPalette(
        panel = panel,
        ink = ink,
        ghost = ink.copy(alpha = 0.10f),
        caseColor = Color.Black,
        label = Color(0xFF6B3A20),
        isNight = true,
    )
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}
