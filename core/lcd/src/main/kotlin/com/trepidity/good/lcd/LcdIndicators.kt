package com.trepidity.good.lcd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DAY_LETTERS = "MTWTFSS"
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * "M T W T F S S" in 14-segment letters, [height] tall overall, with a bar under each day whose bit in [mask]
 * is set (bit 0 = Monday … bit 6 = Sunday). Unset days show a ghost bar.
 */
@Composable
fun WeekdayRow(
    mask: Int,
    height: Dp,
    palette: LcdPalette,
    modifier: Modifier = Modifier,
    contentDescription: String? = DAY_NAMES.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
        .joinToString(" ").ifEmpty { "No days" },
) {
    val letterFraction = 0.72f
    val width = with(LocalDensity.current) {
        val h = height.toPx() * letterFraction
        (7 * segmentCellWidth(h, SegmentKind.FOURTEEN) + 6 * h * 0.45f).toDp()
    }
    val described = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.then(described).size(width, height)) {
        val h = size.height * letterFraction
        val cell = segmentCellWidth(h, SegmentKind.FOURTEEN)
        val step = cell + h * 0.45f
        val barH = size.height * 0.1f
        DAY_LETTERS.forEachIndexed { i, c ->
            val x = i * step
            drawSegmentChar(c, Rect(x, 0f, x + cell, h), SegmentKind.FOURTEEN, palette.ink, palette.ghost)
            val on = mask and (1 shl i) != 0
            drawRect(
                color = if (on) palette.ink else palette.ghost,
                topLeft = Offset(x, size.height - barH),
                size = Size(cell - h * SLANT, barH),
            )
        }
    }
}

/**
 * LCD bar graph: one column per entry of [values] (0..1), each a stack of [levels] horizontal segments lit from the
 * bottom. A null entry is an all-ghost column (no data). Size it with [modifier].
 */
@Composable
fun LcdBarGraph(values: List<Float?>, palette: LcdPalette, modifier: Modifier = Modifier, levels: Int = 8) {
    Canvas(modifier.defaultMinSize(84.dp, 32.dp)) {
        if (values.isEmpty() || levels <= 0) return@Canvas
        val colW = size.width / values.size
        val barW = colW * 0.66f
        val rowH = size.height / levels
        val segH = rowH * 0.72f
        values.forEachIndexed { i, v ->
            val lit = if (v == null) 0 else (v.coerceIn(0f, 1f) * levels).roundToInt().coerceAtLeast(if (v > 0f) 1 else 0)
            val left = i * colW + (colW - barW) / 2
            for (row in 0 until levels) {
                val top = size.height - (row + 1) * rowH + (rowH - segH) / 2
                drawRect(
                    color = if (row < lit) palette.ink else palette.ghost,
                    topLeft = Offset(left, top),
                    size = Size(barW, segH),
                )
            }
        }
    }
}

/**
 * A row of [segments] slanted blocks that fill left to right as [progress] goes 0..1 (the phone's hold-to-stop bar).
 * Size it with [modifier].
 */
@Composable
fun SegmentBar(progress: Float, segments: Int, palette: LcdPalette, modifier: Modifier = Modifier) {
    Canvas(modifier.defaultMinSize(120.dp, 12.dp)) {
        if (segments <= 0) return@Canvas
        val h = size.height
        val lean = h * SLANT * 2
        val pitch = (size.width - lean) / segments
        val gap = min(pitch * 0.25f, h * 0.3f)
        val lit = floor(progress.coerceIn(0f, 1f) * segments + 1e-4f).toInt()
        for (i in 0 until segments) {
            val x = i * pitch
            val block = Path().apply {
                moveTo(x + lean, 0f)
                lineTo(x + pitch - gap + lean, 0f)
                lineTo(x + pitch - gap, h)
                lineTo(x, h)
                close()
            }
            drawPath(block, if (i < lit) palette.ink else palette.ghost)
        }
    }
}

/**
 * [count] tick marks around the edge of a circle, lighting clockwise from 12 o'clock as [progress] goes 0..1
 * (the watch's hold-to-stop seconds track). Every fifth tick is longer. Unlit ticks use [ghost].
 */
@Composable
fun SegmentRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    count: Int = 60,
    ghost: Color = color.copy(alpha = 0.15f),
) {
    Canvas(modifier.defaultMinSize(48.dp, 48.dp)) {
        if (count <= 0) return@Canvas
        val r = min(size.width, size.height) / 2
        val stroke = r * 0.035f
        val outer = r - stroke
        val lit = floor(progress.coerceIn(0f, 1f) * count + 1e-4f).toInt()
        for (i in 0 until count) {
            val angle = -PI / 2 + 2 * PI * i / count
            val len = if (i % 5 == 0) r * 0.11f else r * 0.07f
            val cx = cos(angle).toFloat()
            val sy = sin(angle).toFloat()
            drawLine(
                color = if (i < lit) color else ghost,
                start = center + Offset(cx * (outer - len), sy * (outer - len)),
                end = center + Offset(cx * outer, sy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Butt,
            )
        }
    }
}
