package com.trepidity.good.lcd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.dp

/**
 * The LCD glass: [palette]'s panel colour clipped to [shape] (pass `CircleShape` on the watch), with a subtle
 * inner shade toward the edges and a thin bezel line just inside the border. [content] draws on top.
 */
@Composable
fun LcdPanel(
    palette: LcdPalette,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(palette.panel)
            .drawBehind {
                val edge = Color.Black.copy(alpha = if (palette.isNight) 0.16f else 0.12f)
                drawRect(
                    Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to edge,
                        center = center,
                        radius = size.maxDimension * 0.75f,
                    ),
                )
                drawRect(Brush.verticalGradient(0f to edge, 0.08f to Color.Transparent))
                val inset = 3.dp.toPx()
                inset(inset) {
                    drawOutline(
                        shape.createOutline(size, layoutDirection, this),
                        color = palette.ink.copy(alpha = 0.3f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            },
        content = content,
    )
}
