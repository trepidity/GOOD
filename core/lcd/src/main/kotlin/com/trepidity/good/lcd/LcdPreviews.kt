package com.trepidity.good.lcd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun Instrument(palette: LcdPalette) {
    Column(
        Modifier.background(palette.caseColor).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LcdPanel(palette, Modifier.width(320.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentText("ALM SLP PRO CHK", 12.dp, palette, kind = SegmentKind.FOURTEEN)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    SegmentText(" 6:30", 72.dp, palette)
                    SegmentText("AL1", 20.dp, palette, kind = SegmentKind.FOURTEEN)
                }
                WeekdayRow(0b0011111, 16.dp, palette)
                SegmentText("IN 7:20 · GENTLE", 12.dp, palette, kind = SegmentKind.FOURTEEN)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcdGlyph(Glyph.BELL, lit = true, palette = palette)
                    LcdGlyph(Glyph.LINK, lit = true, palette = palette)
                    LcdGlyph(Glyph.MOON, lit = false, palette = palette)
                    LcdGlyph(Glyph.OH, lit = true, palette = palette, modifier = Modifier.size(28.dp))
                    LcdBarGraph(listOf(0.8f, 0.95f, null, 0.6f, 0.7f, 1f, 0.4f), palette, Modifier.size(100.dp, 36.dp))
                }
                SegmentBar(0.45f, 20, palette, Modifier.fillMaxWidth().height(10.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CaseButton("Set", palette, onClick = {}, modifier = Modifier.width(96.dp))
            CaseButton("Light", palette, onClick = {}, modifier = Modifier.width(96.dp))
            CaseButton("▼/Stop", palette, onClick = {}, onLongClick = {}, modifier = Modifier.width(96.dp))
        }
    }
}

@Preview(name = "Day")
@Composable
private fun DayPreview() = Instrument(LcdPalette.Day)

@Preview(name = "Night glow")
@Composable
private fun NightPreview() = Instrument(LcdPalette.Night)

@Preview(name = "Sunrise")
@Composable
private fun SunrisePreview() {
    Row(Modifier.background(LcdPalette.Night.caseColor).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val palette = LcdPalette.sunrise(p)
            LcdPanel(palette) { SegmentText("6:30", 32.dp, palette, Modifier.padding(8.dp)) }
        }
    }
}

@Preview(name = "Watch", widthDp = 227, heightDp = 227)
@Composable
private fun WatchPreview() {
    val palette = LcdPalette.Night
    LcdPanel(palette, Modifier.size(227.dp), shape = CircleShape) {
        SegmentRing(0.4f, palette.ink, Modifier.size(227.dp))
        Box(Modifier.align(Alignment.Center)) { SegmentText("6:30", 56.dp, palette) }
    }
}
