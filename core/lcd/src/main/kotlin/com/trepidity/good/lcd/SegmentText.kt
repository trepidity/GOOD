package com.trepidity.good.lcd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

/**
 * [text] drawn in segments [height] tall; its width follows from the characters. The LCD is drawn, not typeset,
 * so [contentDescription] is what TalkBack reads (null to leave it silent, e.g. when a parent describes it).
 */
@Composable
fun SegmentText(
    text: String,
    height: Dp,
    palette: LcdPalette,
    modifier: Modifier = Modifier,
    kind: SegmentKind = SegmentKind.SEVEN,
    ghost: Boolean = true,
    contentDescription: String? = text,
) {
    val width = with(LocalDensity.current) { segmentTextWidth(text, height.toPx(), kind).toDp() }
    val described = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.then(described).size(width, height)) {
        drawSegmentText(text, Offset.Zero, size.height, kind, palette.ink, if (ghost) palette.ghost else null)
    }
}

/** Alternates true/false every [periodMs] while [active] (the flashing field being set); always true otherwise. */
@Composable
fun rememberBlink(active: Boolean, periodMs: Int = 500): Boolean {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(active, periodMs) {
        on = true
        while (active) {
            delay(periodMs.toLong())
            on = !on
        }
    }
    return !active || on
}
