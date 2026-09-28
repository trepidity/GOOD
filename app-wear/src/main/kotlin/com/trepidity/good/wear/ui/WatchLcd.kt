package com.trepidity.good.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import com.trepidity.good.lcd.Glyph
import com.trepidity.good.lcd.LcdGlyph
import com.trepidity.good.lcd.LcdPalette
import com.trepidity.good.lcd.LcdPanel
import com.trepidity.good.lcd.SegmentKind
import com.trepidity.good.lcd.SegmentRing
import com.trepidity.good.lcd.SegmentText

/** The watch app's four modes, in MODE order. */
enum class Mode { ALM, SLP, PRO, CHK }

/** Heights as fractions of the screen's diameter; the stack stays inside the round glass at 454 px. */
private const val TAB = 0.042f
private const val TITLE = 0.068f
private const val BIG = 0.235f
private const val LINE = 0.064f
private const val SMALL = 0.054f
private const val ICON = 0.07f

/** Top band of the face: a tap there is the LIGHT button (GLOW), not ▲. */
const val TAB_BAND = 0.27f

/** Indicator icons along the bottom: armed · watch linked · sleep tracking. */
data class Indicators(val armed: Boolean, val linked: Boolean, val sleep: Boolean)

/**
 * One round LCD: mode tabs on top, [body]'s four rows in the middle, indicator icons below, and a 60-tick
 * seconds track round the edge that fills with [hold] (the 2-s SET hold). [body] gets the screen diameter.
 */
@Composable
fun LcdFace(
    mode: Mode,
    palette: LcdPalette,
    hold: Float,
    indicators: Indicators,
    modifier: Modifier = Modifier,
    body: @Composable (Dp) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(palette.caseColor), contentAlignment = Alignment.Center) {
        val s = min(maxWidth, maxHeight)
        LcdPanel(palette, Modifier.size(s), shape = CircleShape) {
            SegmentRing(hold, palette.ink, Modifier.fillMaxSize().padding(s * 0.02f), ghost = palette.ghost)
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(s * 0.022f, Alignment.CenterVertically),
            ) {
                Row(Modifier.height(s * TAB), horizontalArrangement = Arrangement.spacedBy(s * 0.035f)) {
                    val dim = palette.copy(ink = palette.ghost.copy(alpha = palette.ghost.alpha * 3f))
                    Mode.entries.forEach { Seg(it.name, s * TAB, if (it == mode) palette else dim) }
                }
                body(s)
                Row(Modifier.height(s * ICON), horizontalArrangement = Arrangement.spacedBy(s * 0.05f)) {
                    LcdGlyph(Glyph.BELL, indicators.armed, palette, Modifier.size(s * ICON))
                    LcdGlyph(Glyph.LINK, indicators.linked, palette, Modifier.size(s * ICON))
                    LcdGlyph(Glyph.MOON, indicators.sleep, palette, Modifier.size(s * ICON))
                }
            }
        }
    }
}

/** A mode's four fixed-height rows, so switching modes never shifts the layout. */
@Composable
fun Slots(
    s: Dp,
    title: @Composable RowScope.(Dp) -> Unit,
    big: @Composable RowScope.(Dp) -> Unit,
    line: @Composable RowScope.(Dp) -> Unit,
    small: @Composable RowScope.(Dp) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SlotRow(s, TITLE, title)
        SlotRow(s, BIG, big)
        SlotRow(s, LINE, line)
        SlotRow(s, SMALL, small)
    }
}

@Composable
private fun SlotRow(s: Dp, fraction: Float, content: @Composable RowScope.(Dp) -> Unit) {
    Row(
        Modifier.height(s * fraction),
        horizontalArrangement = Arrangement.spacedBy(s * 0.03f, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(s * fraction) }
}

/** LCD text with no semantics of its own: the face describes itself as a whole. */
@Composable
fun Seg(text: String, height: Dp, palette: LcdPalette, kind: SegmentKind = SegmentKind.FOURTEEN) {
    SegmentText(text, height, palette, kind = kind, contentDescription = null)
}

/** The CHK "passed" mark, drawn like a fixed LCD icon. */
@Composable
fun CheckMark(palette: LcdPalette, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val path = Path().apply {
            moveTo(w * 0.12f, w * 0.55f)
            lineTo(w * 0.40f, w * 0.82f)
            lineTo(w * 0.90f, w * 0.18f)
        }
        drawPath(path, palette.ink, style = Stroke(w * 0.16f, cap = StrokeCap.Square, join = StrokeJoin.Miter))
    }
}

@Composable
fun AlarmBody(s: Dp, p: LcdPalette, channel: String, time: String, meridiem: String?, line: String, small: String) {
    Slots(
        s,
        title = { h -> Seg(channel, h, p); if (meridiem != null) Seg(meridiem, h * 0.7f, p) },
        big = { h -> Seg(time, h, p, SegmentKind.SEVEN) },
        line = { h -> Seg(line, h, p) },
        small = { h -> Seg(small, h, p) },
    )
}

@Composable
fun SleepBody(s: Dp, p: LcdPalette, total: String, bedWake: String?, goal: String, fromHealthConnect: Boolean, message: String?) {
    Slots(
        s,
        title = { h -> Seg("SLP", h, p); LcdGlyph(Glyph.OH, fromHealthConnect, p, Modifier.size(h * 1.3f)) },
        big = { h -> if (message != null) Seg(message, h * 0.42f, p) else Seg(total, h, p, SegmentKind.SEVEN) },
        line = { h -> if (bedWake != null) Seg(bedWake, h, p, SegmentKind.SEVEN) else Seg("NO DATA", h, p) },
        small = { h -> Seg(goal, h, p) },
    )
}

/** One PRO row: [value] in seven-segment, with an optional 14-segment [prefix] (the "+" of an offset). */
@Composable
fun ProfileBody(s: Dp, p: LcdPalette, title: String, value: String, prefix: String?, valueIsText: Boolean, line: String, small: String) {
    Slots(
        s,
        title = { h -> Seg(title, h, p) },
        big = { h ->
            when {
                valueIsText -> Seg(value, h * 0.5f, p)
                else -> {
                    if (prefix != null) Seg(prefix, h * 0.5f, p)
                    Seg(value, h, p, SegmentKind.SEVEN)
                }
            }
        },
        line = { h -> Seg(line, h, p) },
        small = { h -> Seg(small, h, p) },
    )
}

@Composable
fun CheckBody(s: Dp, p: LcdPalette, index: String, code: String, ok: Boolean?, blinkOn: Boolean, detail: String) {
    Slots(
        s,
        title = { h -> Seg("CHK $index", h, p) },
        big = { h -> Box(Modifier.height(h), contentAlignment = Alignment.Center) { if (blinkOn) Seg(code, h * 0.5f, p) } },
        line = { h ->
            when (ok) {
                true -> { CheckMark(p, h); Seg("OK", h, p) }
                false -> if (blinkOn) Seg("FAIL", h, p)
                null -> Seg("TEST", h, p)
            }
        },
        small = { h -> Seg(detail, h, p) },
    )
}
