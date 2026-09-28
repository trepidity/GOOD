package com.trepidity.good.lcd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Fixed LCD indicator icons. */
enum class Glyph {
    /** Alarm armed. */
    BELL,

    /** Watch linked: two chain links. */
    LINK,

    /** Sleep tracking. */
    MOON,

    /** Sleep data came from OHealth. */
    OH,
}

/**
 * One indicator icon, square, in [palette]'s ink when [lit] and in ghost otherwise (like a printed LCD icon
 * that is always faintly there). Defaults to 20 dp; size it with [modifier].
 */
@Composable
fun LcdGlyph(glyph: Glyph, lit: Boolean, palette: LcdPalette, modifier: Modifier = Modifier) {
    Canvas(modifier.defaultMinSize(20.dp, 20.dp)) {
        val s = min(size.width, size.height)
        val color = if (lit) palette.ink else palette.ghost
        translate((size.width - s) / 2, (size.height - s) / 2) {
            when (glyph) {
                Glyph.BELL -> drawBell(s, color)
                Glyph.LINK -> drawLink(s, color)
                Glyph.MOON -> drawMoon(s, color)
                Glyph.OH -> drawOh(s, color, palette.panel)
            }
        }
    }
}

private fun DrawScope.drawBell(s: Float, color: Color) {
    val body = Path().apply {
        moveTo(0.16f * s, 0.74f * s)
        cubicTo(0.25f * s, 0.64f * s, 0.24f * s, 0.50f * s, 0.26f * s, 0.40f * s)
        cubicTo(0.29f * s, 0.22f * s, 0.40f * s, 0.16f * s, 0.50f * s, 0.16f * s)
        cubicTo(0.60f * s, 0.16f * s, 0.71f * s, 0.22f * s, 0.74f * s, 0.40f * s)
        cubicTo(0.76f * s, 0.50f * s, 0.75f * s, 0.64f * s, 0.84f * s, 0.74f * s)
        close()
    }
    drawPath(body, color)
    drawCircle(color, 0.05f * s, Offset(0.5f * s, 0.11f * s))
    drawCircle(color, 0.08f * s, Offset(0.5f * s, 0.83f * s))
}

private fun DrawScope.drawLink(s: Float, color: Color) {
    val stroke = Stroke(width = 0.1f * s)
    val r = CornerRadius(0.14f * s)
    val links = Path().apply {
        addRoundRect(RoundRect(0.06f * s, 0.36f * s, 0.60f * s, 0.64f * s, r))
        addRoundRect(RoundRect(0.40f * s, 0.36f * s, 0.94f * s, 0.64f * s, r))
    }
    rotate(-35f, Offset(s / 2, s / 2)) { drawPath(links, color, style = stroke) }
}

private fun DrawScope.drawMoon(s: Float, color: Color) {
    val disc = Path().apply { addOval(Rect(Offset(0.5f * s, 0.5f * s), 0.36f * s)) }
    val bite = Path().apply { addOval(Rect(Offset(0.68f * s, 0.36f * s), 0.32f * s)) }
    drawPath(Path().apply { op(disc, bite, PathOperation.Difference) }, color)
}

private fun DrawScope.drawOh(s: Float, color: Color, knockout: Color) {
    val badge = Path().apply {
        addRoundRect(RoundRect(0.02f * s, 0.2f * s, 0.98f * s, 0.8f * s, CornerRadius(0.12f * s)))
    }
    drawPath(badge, color)
    val h = 0.4f * s
    val w = segmentTextWidth("OH", h, SegmentKind.FOURTEEN)
    drawSegmentText("OH", Offset((s - w) / 2, (s - h) / 2), h, SegmentKind.FOURTEEN, knockout, null)
}
