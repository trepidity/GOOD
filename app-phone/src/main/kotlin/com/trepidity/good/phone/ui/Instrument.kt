package com.trepidity.good.phone.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trepidity.good.lcd.CaseButton
import com.trepidity.good.lcd.Glyph
import com.trepidity.good.lcd.LcdBarGraph
import com.trepidity.good.lcd.LcdGlyph
import com.trepidity.good.lcd.LcdPalette
import com.trepidity.good.lcd.LcdPanel
import com.trepidity.good.lcd.SegmentBar
import com.trepidity.good.lcd.SegmentKind
import com.trepidity.good.lcd.SegmentText
import com.trepidity.good.lcd.WeekdayRow
import com.trepidity.good.lcd.rememberBlink
import com.trepidity.good.phone.CheckId
import com.trepidity.good.sleep.SleepSource
import com.trepidity.good.wake.ProfileRow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** The whole app: one LCD panel, a LIGHT button and four case buttons that change meaning by mode (SPEC UX). */
@Composable
fun Instrument(model: InstrumentModel) {
    val s by model.state.collectAsState()
    val palette = if (s.glow) LcdPalette.Night else LcdPalette.Day
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(palette.caseColor, Color.White, 0.06f), palette.caseColor, Color.Black))),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                "GOOD · ALARM CHRONO",
                style = TextStyle(color = palette.label, fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            var drag by remember { mutableFloatStateOf(0f) }
            val swipePx = with(LocalDensity.current) { 72.dp.toPx() }
            LcdPanel(
                palette,
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .aspectRatio(0.78f)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onDragEnd = { if (abs(drag) > swipePx) model.mode(forward = drag < 0) },
                        ) { _, dx -> drag += dx }
                    },
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeBar(s.mode, palette)
                    when (s.mode) {
                        Mode.ALM -> AlmFace(model, s, palette, now)
                        Mode.SLP -> SlpFace(model, s, palette)
                        Mode.PRO -> ProFace(model, s, palette)
                        Mode.CHK -> ChkFace(model, s, palette)
                    }
                    Spacer(Modifier.weight(1f))
                    if (holdProgress > 0f) SegmentBar(holdProgress, 20, palette, Modifier.fillMaxWidth().height(10.dp))
                }
            }
            Spacer(Modifier.weight(0.01f).heightIn(min = 14.dp))
            CaseButton("LIGHT", palette, onClick = model::light, modifier = Modifier.width(150.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                CaseButton(
                    "SET", palette, onClick = model::set, modifier = Modifier.weight(1f),
                    onLongClick = { model.holdSet(); holdProgress = 0f },
                    onHoldProgress = { holdProgress = it },
                    contentDescription = "Set. Hold two seconds: ${holdMeaning(s)}",
                )
                CaseButton("▲", palette, onClick = model::up, modifier = Modifier.weight(1f), repeatOnHold = true, contentDescription = "Up")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                CaseButton("MODE", palette, onClick = { model.mode() }, modifier = Modifier.weight(1f), contentDescription = "Mode, now ${s.mode}")
                CaseButton("▼", palette, onClick = model::down, modifier = Modifier.weight(1f), repeatOnHold = true, contentDescription = "Down")
            }
        }
    }
}

private fun holdMeaning(s: UiState) = when (s.mode) {
    Mode.ALM -> if (s.almEdit != null) "save" else "arm or disarm"
    Mode.SLP -> if (s.slpEdit != null) "save" else "edit this night"
    Mode.PRO -> "sixty second preview"
    Mode.CHK -> "run the checks again"
}

@Composable
private fun ModeBar(mode: Mode, palette: LcdPalette) {
    val dim = palette.copy(ink = palette.ghost.copy(alpha = palette.ghost.alpha * 2.5f))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Mode.entries.forEach { m ->
            SegmentText(m.name, 18.dp,
                if (m == mode) palette else dim, kind = SegmentKind.FOURTEEN, ghost = false,
                contentDescription = if (m == mode) "Mode ${m.name}" else null)
        }
    }
}

/** Uppercase and restrict to what the 14-segment font draws. */
private fun lcd(s: String) = s.uppercase().map { if (it.isLetterOrDigit() || it in " -+/*:.·") it else ' ' }.joinToString("")

@Composable
private fun Line(text: String, palette: LcdPalette, height: Dp = 18.dp, blink: Boolean = false) {
    val on = rememberBlink(blink)
    SegmentText(lcd(if (on) text else " ".repeat(text.length)), height, palette, kind = SegmentKind.FOURTEEN, contentDescription = text)
}

@Composable
private fun ColumnScope.AlmFace(model: InstrumentModel, s: UiState, palette: LcdPalette, now: Instant) {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val edit = s.almEdit
    val a = edit?.second ?: model.alarm(s.channel)
    val field = edit?.first
    val blink = rememberBlink(field != null)
    val h12 = if (is24) a.hour else (a.hour % 12).let { if (it == 0) 12 else it }
    val hourText = if (field == AlmField.HOUR && !blink) "  " else "%2d".format(h12)
    val minText = if (field == AlmField.MINUTE && !blink) "  " else "%02d".format(a.minute)
    val entry = model.entryFor(s.channel)
    val profileName = model.profiles.value.firstOrNull { it.id == a.profileId }?.name ?: a.profileId

    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SegmentText("$hourText:$minText", 96.dp, palette, contentDescription = "Alarm ${s.channel} at ${a.hour}:${"%02d".format(a.minute)}")
        Column(horizontalAlignment = Alignment.End) {
            SegmentText("AL${s.channel}", 24.dp, palette, kind = SegmentKind.FOURTEEN)
            if (!is24) SegmentText(if (a.hour < 12) "AM" else "PM", 16.dp, palette, kind = SegmentKind.FOURTEEN)
        }
    }
    val dayBit = field?.day
    val mask = if (dayBit != null && !blink) a.repeatDays xor (1 shl dayBit) else a.repeatDays
    WeekdayRow(mask, 30.dp, palette)
    when {
        s.banner != null -> Line(s.banner, palette, 22.dp)
        field == null -> Line(
            listOfNotNull(if (a.enabled) model.countdown(entry, now) ?: "ARMED" else "OFF", profileName).joinToString(" · "),
            palette, 20.dp,
        )
        field.day != null -> Line("${field.code} ${if (a.repeatDays and (1 shl field.day!!) != 0) "ON" else "--"}", palette, 20.dp)
        field == AlmField.PROFILE -> Line("PRO $profileName", palette, 20.dp, blink = true)
        field == AlmField.TONE -> Line("TONE ${a.tone}", palette, 20.dp, blink = true)
        field == AlmField.TARGET -> Line("SND ${a.soundTarget}", palette, 20.dp, blink = true)
        else -> Line("SET ${field.code}", palette, 20.dp)
    }
    if (field == null && a.repeatDays == 0 && a.enabled) Line("ONCE", palette, 14.dp)
    Spacer(Modifier.weight(1f))
    // All four channels at a glance, like the alarm list a real watch never had room for.
    (1L..4L).forEach { ch ->
        val c = if (ch == s.channel) a else model.alarm(ch)
        val mark = if (ch == s.channel) "*" else " "
        Line("${mark}AL$ch ${if (c.enabled) "%2d:%02d".format(c.hour, c.minute) else "--:--"} ${if (c.enabled) daysShort(c.repeatDays) else "OFF"}", palette, 15.dp)
    }
    Glyphs(palette, armed = a.enabled, linked = s.checks.firstOrNull { it.id == CheckId.LINK }?.ok == true,
        tracking = s.checks.any { (it.id == CheckId.HC || it.id == CheckId.ACT) && it.ok }, oh = false)
}

@Composable
private fun Glyphs(palette: LcdPalette, armed: Boolean, linked: Boolean, tracking: Boolean, oh: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LcdGlyph(Glyph.BELL, armed, palette, Modifier.size(26.dp))
        LcdGlyph(Glyph.LINK, linked, palette, Modifier.size(26.dp))
        LcdGlyph(Glyph.MOON, tracking, palette, Modifier.size(26.dp))
        LcdGlyph(Glyph.OH, oh, palette, Modifier.size(26.dp))
    }
}

private fun daysShort(mask: Int): String = when (mask) {
    0 -> "ONCE"
    0b1111111 -> "DAILY"
    0b0011111 -> "WKDAY"
    0b1100000 -> "WKEND"
    else -> "MTWTFSS".mapIndexed { i, c -> if (mask and (1 shl i) != 0) c else '-' }.joinToString("")
}

private fun hm(min: Int?) = min?.let { "${it / 60}:${"%02d".format(it % 60)}" } ?: "-:--"

private fun clock(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
    return "${t.hour}:${"%02d".format(t.minute)}"
}

@Composable
private fun ColumnScope.SlpFace(model: InstrumentModel, s: UiState, palette: LcdPalette) {
    val sessions by model.sessions.collectAsState()
    val nights by model.nights.collectAsState()
    val edit = s.slpEdit
    val session = sessions.getOrNull(s.lap)
    val wakeDate = edit?.wakeDate ?: session?.wakeDate?.let(LocalDate::parse) ?: LocalDate.now().minusDays(s.lap.toLong())

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Line("LAP %02d  %d-%02d".format(s.lap + 1, wakeDate.monthValue, wakeDate.dayOfMonth), palette, 18.dp)
        LcdGlyph(Glyph.OH, session?.source == SleepSource.HEALTH_CONNECT.name, palette, Modifier.size(24.dp))
        if (session?.edited == true) Line("ED", palette, 14.dp)
    }
    SegmentText(hm(session?.totalSleepMin), 88.dp, palette, contentDescription = "Slept ${hm(session?.totalSleepMin)}")
    if (edit == null) {
        val bed = session?.let { clock(it.bedtimeAnchor ?: it.start) } ?: "--:--"
        val up = session?.let { clock(it.end) } ?: "--:--"
        Line("BED $bed  UP $up", palette, 18.dp)
    } else {
        val (label, value) = when (edit.field) {
            SlpField.BED -> "BED" to clock(edit.bed.toEpochMilli())
            SlpField.WAKE -> "WAKE" to clock(edit.wake.toEpochMilli())
            SlpField.GOAL -> "GOAL" to hm(edit.goalMin)
            SlpField.REMIND -> "REMIND" to if (edit.remind) "ON" else "OFF"
        }
        Line("$label $value", palette, 22.dp, blink = true)
    }
    val goal = s.sleepGoalMin.coerceAtLeast(1)
    val byDate = nights.associateBy { it.wakeDate }
    val today = LocalDate.now()
    LcdBarGraph((6 downTo 0).map { d -> byDate[today.minusDays(d.toLong())]?.let { (it.totalSleepMin.toFloat() / goal).coerceIn(0f, 1f) } }, palette,
        Modifier.fillMaxWidth().height(56.dp))
    val (avg7, avg30, debt) = model.sleepAverages()
    if (s.banner != null) Line(s.banner, palette, 22.dp) else {
        Line("7D ${hm(avg7)} 30D ${hm(avg30)}", palette, 16.dp)
        Line("DEBT ${hm(debt)} SPR ${model.bedtimeSpread() ?: "--"}", palette, 16.dp)
    }
}

@Composable
private fun ColumnScope.ProFace(model: InstrumentModel, s: UiState, palette: LcdPalette) {
    val p = model.currentProfile(s)
    val editing = s.proDraft != null
    Line("P${s.proProfile + 1} ${p.name}", palette, 22.dp, blink = editing && s.proRow == 0)
    val row = ProfileRow.entries.getOrNull(s.proRow - 1)
    if (row != null) {
        val v = row.get(p)
        val big = when (row) {
            ProfileRow.FULL -> "+$v:00"
            ProfileRow.SIL -> "$v"
            else -> "$v:00"
        }
        val on = rememberBlink(editing)
        SegmentText(if (on) big else " ".repeat(big.length), 80.dp, palette, contentDescription = "${row.code} $v minutes")
    } else {
        SegmentText("P${s.proProfile + 1}", 80.dp, palette)
    }
    ProfileRow.entries.forEachIndexed { i, r ->
        val label = when (r) {
            ProfileRow.LIGHT -> "INT 1 LIGHT %d:00"
            ProfileRow.BUZZ -> "INT 2 BUZZ %d:00"
            ProfileRow.TONE -> "INT 3 TONE %d:00"
            ProfileRow.FULL -> "INT 4 FULL +%d:00"
            ProfileRow.SIL -> "SIL %d"
        }.format(r.get(p))
        Line((if (s.proRow == i + 1) "*" else " ") + label, palette, 15.dp)
    }
    if (s.banner != null) Line(s.banner, palette, 22.dp)
}

@Composable
private fun ColumnScope.ChkFace(model: InstrumentModel, s: UiState, palette: LcdPalette) {
    val item = s.checks.getOrNull(s.chkIndex)
    val action = ChkAction.entries.getOrNull(s.chkIndex - s.checks.size)
    if (s.checks.isEmpty()) {
        Line(if (s.checking) "TESTING..." else "NO DATA", palette, 30.dp)
        return
    }
    val code = item?.id?.code ?: action?.code ?: ""
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SegmentText(code, 56.dp, palette, kind = SegmentKind.FOURTEEN)
        when {
            item != null -> Line(if (item.ok) "OK" else "FIX", palette, 40.dp, blink = !item.ok)
            else -> Line("SET", palette, 40.dp)
        }
    }
    Line(item?.id?.title ?: if (action == ChkAction.TST) "Test alarm +3 min" else "Export JSON", palette, 16.dp)
    Line(item?.detail ?: if (action == ChkAction.TST) "Both devices ring" else "Pick a file", palette, 14.dp)
    Spacer(Modifier.height(8.dp))
    // A segment-test strip: every item at a glance, failing ones blinking.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.checks.forEachIndexed { i, c ->
            val on = rememberBlink(!c.ok)
            SegmentText(if (on || i == s.chkIndex) c.id.code.take(3) else "   ", 14.dp,
                if (i == s.chkIndex) palette else palette.copy(ink = palette.ink.copy(alpha = 0.55f)), kind = SegmentKind.FOURTEEN, contentDescription = null)
        }
    }
    if (s.banner != null) Line(s.banner, palette, 22.dp)
    if (s.checking) Line("TESTING...", palette, 14.dp)
}
