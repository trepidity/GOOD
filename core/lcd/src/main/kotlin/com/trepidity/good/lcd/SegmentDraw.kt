package com.trepidity.good.lcd

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.hypot
import kotlin.math.min

/** Segment font a string is drawn in: [SEVEN] for digits and the few letters it can show, [FOURTEEN] for text. */
enum class SegmentKind { SEVEN, FOURTEEN }

/** tan 6°: the top of every glyph leans right by this fraction of its height. */
internal const val SLANT = 0.105f

private fun SegmentKind.uprightWidth(h: Float) = when (this) {
    SegmentKind.SEVEN -> h * 0.5f
    SegmentKind.FOURTEEN -> h * 0.6f
}

internal fun SegmentKind.thickness(h: Float) = when (this) {
    SegmentKind.SEVEN -> h * 0.11f
    SegmentKind.FOURTEEN -> h * 0.085f
}

private fun SegmentKind.mask(c: Char) = when (this) {
    SegmentKind.SEVEN -> SevenSegment.mask(c)
    SegmentKind.FOURTEEN -> FourteenSegment.mask(c)
}

private fun gap(h: Float) = h * 0.14f
private fun narrowWidth(h: Float) = h * 0.1f
private fun Char.isNarrow() = this == ':' || this == '.' || this == '·'

/** Width of one full character cell, slant included, for glyphs [height] tall. */
fun segmentCellWidth(height: Float, kind: SegmentKind): Float = kind.uprightWidth(height) + height * SLANT

/** Width [drawSegmentText] needs for [text] at [height]: a cell per character, a narrow slot per ':', '.' or '·'. */
fun segmentTextWidth(text: String, height: Float, kind: SegmentKind): Float {
    if (text.isEmpty()) return 0f
    var width = 0f
    for (c in text) width += (if (c.isNarrow()) narrowWidth(height) else kind.uprightWidth(height)) + gap(height)
    return width - gap(height) + height * SLANT
}

/**
 * Draws [text] from [topLeft], [height] tall: lit segments in [ink], every other segment in [ghost]
 * (or nothing when [ghost] is null). ':', '.' and '·' are always-lit dots in a narrow slot between cells.
 */
fun DrawScope.drawSegmentText(
    text: String,
    topLeft: Offset,
    height: Float,
    kind: SegmentKind,
    ink: Color,
    ghost: Color?,
) {
    var x = topLeft.x
    for (c in text) {
        val body = if (c.isNarrow()) narrowWidth(height) else kind.uprightWidth(height)
        drawSegmentChar(c, Rect(x, topLeft.y, x + body + height * SLANT, topLeft.y + height), kind, ink, ghost)
        x += body + gap(height)
    }
}

/**
 * Draws one character into [rect], slanted ~6° to the right. Its upright body is `rect.width - rect.height * 0.105`
 * wide; the rest is the lean. Characters the font lacks draw as all-ghost (blank).
 */
fun DrawScope.drawSegmentChar(char: Char, rect: Rect, kind: SegmentKind, ink: Color, ghost: Color?) {
    val h = rect.height
    val w = rect.width - h * SLANT
    if (char.isNarrow()) {
        drawDots(char, rect.left, rect.top, w, h, kind.thickness(h) * 1.15f, ink)
        return
    }
    val mask = kind.mask(char)
    val lit = Path()
    val off = Path()
    segmentBars(kind, w, h).forEachIndexed { i, s ->
        val target = if (mask and (1 shl i) != 0) lit else off
        target.slantedBar(s[0], s[1], s[2], s[3], s[4], s[5], rect.left, rect.top, h)
    }
    if (ghost != null) drawPath(off, ghost)
    drawPath(lit, ink)
}

private fun DrawScope.drawDots(c: Char, left: Float, top: Float, w: Float, h: Float, d: Float, ink: Color) {
    val ys = when (c) {
        ':' -> floatArrayOf(h * 0.3f, h * 0.7f)
        '.' -> floatArrayOf(h - d / 2)
        else -> floatArrayOf(h * 0.5f)
    }
    val path = Path()
    for (y in ys) path.slantedBar(w / 2 - d / 2, y, w / 2 + d / 2, y, d, 0f, left, top, h)
    drawPath(path, ink)
}

/** Segment centre lines as [x0, y0, x1, y1, thickness, taper], in bit order of the kind's table. */
private fun segmentBars(kind: SegmentKind, w: Float, h: Float): List<FloatArray> {
    val t = kind.thickness(h)
    val g = t * 0.2f
    val taper = t / 2
    val l = t / 2
    val r = w - t / 2
    val top = t / 2
    val bot = h - t / 2
    val m = h / 2
    val outer = listOf(
        floatArrayOf(l + g, top, r - g, top, t, taper),
        floatArrayOf(r, top + g, r, m - g, t, taper),
        floatArrayOf(r, m + g, r, bot - g, t, taper),
        floatArrayOf(l + g, bot, r - g, bot, t, taper),
        floatArrayOf(l, m + g, l, bot - g, t, taper),
        floatArrayOf(l, top + g, l, m - g, t, taper),
    )
    if (kind == SegmentKind.SEVEN) return outer + listOf(floatArrayOf(l + g, m, r - g, m, t, taper))
    val c = w / 2
    val d = t * 1.1f
    val e = t * 0.75f
    val dt = t * 0.8f
    val dp = t * 0.3f
    return outer + listOf(
        floatArrayOf(l + g, m, c - g, m, t, taper),
        floatArrayOf(c + g, m, r - g, m, t, taper),
        floatArrayOf(l + d, top + d, c - e, m - e, dt, dp),
        floatArrayOf(c, top + g, c, m - g, t, taper),
        floatArrayOf(r - d, top + d, c + e, m - e, dt, dp),
        floatArrayOf(c - e, m + e, l + d, bot - d, dt, dp),
        floatArrayOf(c, m + g, c, bot - g, t, taper),
        floatArrayOf(c + e, m + e, r - d, bot - d, dt, dp),
    )
}

/** Adds a hexagonal bar from (x0,y0) to (x1,y1) with pointed ends, sheared by [SLANT] within a glyph [h] tall. */
private fun Path.slantedBar(
    x0: Float, y0: Float, x1: Float, y1: Float, thick: Float, taper: Float, ox: Float, oy: Float, h: Float,
) {
    val dx = x1 - x0
    val dy = y1 - y0
    val len = hypot(dx, dy)
    if (len <= 0f) return
    val ux = dx / len
    val uy = dy / len
    val nx = -uy * thick / 2
    val ny = ux * thick / 2
    val tp = min(taper, len / 2)
    val xs = floatArrayOf(x0, x0 + ux * tp + nx, x1 - ux * tp + nx, x1, x1 - ux * tp - nx, x0 + ux * tp - nx)
    val ys = floatArrayOf(y0, y0 + uy * tp + ny, y1 - uy * tp + ny, y1, y1 - uy * tp - ny, y0 + uy * tp - ny)
    for (i in xs.indices) {
        val px = ox + xs[i] + (h - ys[i]) * SLANT
        val py = oy + ys[i]
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}
