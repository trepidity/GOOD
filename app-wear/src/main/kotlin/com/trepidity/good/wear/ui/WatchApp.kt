package com.trepidity.good.wear.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.trepidity.good.lcd.LcdPalette
import com.trepidity.good.lcd.rememberBlink
import com.trepidity.good.model.BedtimeMessage
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.StageType
import com.trepidity.good.model.ToggleCommand
import com.trepidity.good.sleep.SleepAction
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.wear.WatchFormat
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.sleep.PassiveSleep
import com.trepidity.good.wear.sleep.WatchImUp
import com.trepidity.good.wear.sync.WatchSync
import com.trepidity.good.wear.wake.WakeStageService
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private const val GLOW_MS = 3_000L
private const val FLASH_MS = 2_000L
private const val TEST_MS = 40_000L
private const val PRO_ROWS = 6

/** A transient LCD message ("SENT", "GOOD NIGHT", "MORNING") shown in [mode] for two seconds. */
private data class Flash(val mode: Mode, val text: String)

/**
 * The watch app: the phone's four modes as views of its data (REVIEW U6). Tap the top half for ▲, the bottom half
 * for ▼, the mode tabs for GLOW; swipe sideways for MODE; hold 2 s for SET. A rightward swipe on ALM calls [onExit]
 * (the system swipe-to-close is off so swipes can change mode). [resumes] changes on every resume, to re-run CHK.
 */
@Composable
fun WatchApp(resumes: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as WearApplication
    val snapshot by WatchScheduleStore.snapshot.collectAsState()
    val summary by WatchScheduleStore.sleepSummary.collectAsState()

    var mode by rememberSaveable { mutableStateOf(Mode.ALM) }
    var channel by rememberSaveable {
        mutableIntStateOf(WatchScheduleStore.next(context)?.instance?.alarmId?.toInt()?.takeIf { it in 1..4 } ?: 1)
    }
    var proRow by rememberSaveable { mutableIntStateOf(0) }
    var chkRow by rememberSaveable { mutableIntStateOf(0) }
    var glow by remember { mutableStateOf(false) }
    var hold by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    var testing by remember { mutableStateOf<CheckItem?>(null) }
    var checkRuns by remember { mutableIntStateOf(0) }
    var checks by remember { mutableStateOf<Map<CheckItem, CheckResult>>(emptyMap()) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }

    LaunchedEffect(resumes, checkRuns) { checks = WatchChecks.run(context) }
    LaunchedEffect(glow) { if (glow) { delay(GLOW_MS); glow = false } }
    LaunchedEffect(flash) { if (flash != null) { delay(FLASH_MS); flash = null } }
    LaunchedEffect(testing) { if (testing != null) { delay(TEST_MS); testing = null } }

    var asked by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && asked == Manifest.permission.ACTIVITY_RECOGNITION) PassiveSleep.register(context)
        // Denied for good: the dialog won't show again, so the fix is in Settings.
        if (!granted && (context as? Activity)?.shouldShowRequestPermissionRationale(asked) == false) {
            openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        }
        checkRuns++
    }
    fun request(name: String) {
        asked = name
        permission.launch(name)
    }

    val palette = if (glow) LcdPalette.Night else LcdPalette.Day
    val shown = entryFor(snapshot, channel, now)
    val next = WatchScheduleStore.next(context).takeIf { snapshot != null }
    val chkItem = CheckItem.entries[chkRow]
    val chkResult = checks[chkItem]
    val blinkOn = rememberBlink(mode == Mode.CHK && chkResult?.ok == false)
    val indicators = Indicators(
        armed = snapshot?.entries?.any { !it.instance.state.isTerminal } == true,
        linked = checks[CheckItem.LINK]?.ok == true,
        sleep = checks[CheckItem.ACT]?.ok == true,
    )

    fun step(delta: Int) {
        when (mode) {
            Mode.ALM -> channel = (channel - 1 + delta).mod(4) + 1
            Mode.SLP -> Unit
            Mode.PRO -> proRow = (proRow + delta).mod(PRO_ROWS)
            Mode.CHK -> chkRow = (chkRow + delta).mod(CheckItem.entries.size)
        }
    }

    fun changeMode(delta: Int) {
        if (delta < 0 && mode == Mode.ALM) return onExit()
        mode = Mode.entries[(mode.ordinal + delta).mod(Mode.entries.size)]
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        scope.launch { delay(90); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

    fun set() {
        when (mode) {
            Mode.ALM -> {
                val cmd = ToggleCommand(channel.toLong(), System.currentTimeMillis())
                flash = Flash(Mode.ALM, "SEND")
                scope.launch {
                    val sent = app.appScope.async { WatchSync.send(app, DataLayerPaths.CMD_TOGGLE, SyncCodec.encodeAny(cmd)) }.await()
                    flash = Flash(Mode.ALM, if (sent) "SENT" else "QUEUED")
                }
            }
            Mode.SLP -> {
                val at = System.currentTimeMillis()
                if (WatchScheduleStore.sleepAction(context) == SleepAction.UP) {
                    WatchImUp.record(context, Instant.ofEpochMilli(at))
                    // "GOOD MORNING" is wider than the round glass at SleepBody's message size; "GOOD NIGHT" fits.
                    flash = Flash(Mode.SLP, "MORNING")
                } else {
                    WatchScheduleStore.recordLocalBed(context, at)
                    flash = Flash(Mode.SLP, "GOOD NIGHT")
                    app.appScope.launch { WatchSync.send(app, DataLayerPaths.SLEEP_BEDTIME, SyncCodec.encodeAny(BedtimeMessage(at))) }
                }
            }
            Mode.PRO -> Unit
            Mode.CHK -> when (chkItem) {
                CheckItem.EXA -> openSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                CheckItem.FSI -> openSettings(context, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                CheckItem.SCR -> openSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                CheckItem.PWR -> openSettings(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                CheckItem.NTF -> request(Manifest.permission.POST_NOTIFICATIONS)
                CheckItem.ACT -> request(Manifest.permission.ACTIVITY_RECOGNITION)
                CheckItem.BODY -> checkRuns++
                CheckItem.LINK -> scope.launch {
                    app.appScope.async { WatchSync.flush(app) }.await()
                    checkRuns++
                }
                CheckItem.BUZ, CheckItem.SND -> if (testing != null) {
                    context.startService(WakeStageService.actionIntent(context, WakeStageService.ACTION_STOP_TEST))
                    testing = null
                } else {
                    val action = if (chkItem == CheckItem.BUZ) WakeStageService.ACTION_TEST_HAPTICS else WakeStageService.ACTION_TEST_SOUND
                    ContextCompat.startForegroundService(context, WakeStageService.actionIntent(context, action))
                    testing = chkItem
                }
            }
        }
    }

    val holdEnabled = when (mode) {
        Mode.PRO -> false
        Mode.CHK -> chkResult != null && (chkResult.ok != true || chkItem == CheckItem.LINK || chkItem == CheckItem.BODY)
        else -> true
    }
    val sleepAction = if (mode == Mode.SLP) WatchScheduleStore.sleepAction(context) else null
    val setLabel = when (mode) {
        Mode.ALM -> "Arm or disarm alarm $channel"
        Mode.SLP -> if (sleepAction == SleepAction.UP) "Log wake-up now" else "Log bedtime now"
        Mode.PRO -> null
        Mode.CHK -> if (holdEnabled) if (chkResult?.ok == null) "Start or stop test" else "Fix or recheck" else null
    }
    val message = flash?.takeIf { it.mode == mode }?.text

    val description = when (mode) {
        Mode.ALM -> if (shown == null) "Alarm $channel off" else buildString {
            append("Alarm $channel, ${WatchFormat.clock(context, shown.instance.scheduledAtEpochMs)}")
            WatchFormat.meridiem(context, shown.instance.scheduledAtEpochMs)?.let { append(" $it") }
            append(", ${WatchFormat.countdownSpeech(now, shown.instance.scheduledAtEpochMs)}, ${shown.profile.name} profile")
        }
        Mode.SLP -> summary?.totalSleepMin?.let { "Last night ${it / 60} hours ${it % 60} minutes, goal ${summary!!.goalMin / 60} hours" }
            ?: "No sleep data yet"
        Mode.PRO -> proRows(next).getOrNull(proRow)?.speech ?: "No alarm scheduled"
        Mode.CHK -> "${chkItem.speech}: ${chkResult?.speech ?: "checking"}"
    } + (message?.let { ". $it" } ?: "")

    LcdFace(
        mode = mode,
        palette = palette,
        hold = hold,
        indicators = indicators,
        modifier = Modifier
            .clearAndSetSemantics {
                contentDescription = "${mode.name} mode. $description"
                onClick("Glow") { glow = true; true }
                if (setLabel != null) onLongClick(setLabel) { set(); true }
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Next mode") { changeMode(1); true },
                    CustomAccessibilityAction("Previous mode") { changeMode(-1); true },
                    if (mode == Mode.SLP) null else CustomAccessibilityAction("Up") { step(-1); true },
                    if (mode == Mode.SLP) null else CustomAccessibilityAction("Down") { step(1); true },
                )
            }
            .lcdGestures(
                holdEnabled = holdEnabled,
                onTap = { at, size ->
                    when {
                        at.y < size.height * TAB_BAND -> glow = !glow
                        at.y < size.height / 2 -> step(-1)
                        else -> step(1)
                    }
                },
                onSwipe = { dir -> changeMode(-dir) },
                onHold = { hold = it },
                onSet = { set() },
            ),
    ) { s ->
        when (mode) {
            Mode.ALM -> {
                val at = shown?.instance?.scheduledAtEpochMs
                AlarmBody(
                    s, palette,
                    channel = WatchFormat.channel(channel.toLong()),
                    time = at?.let { WatchFormat.clock(context, it) } ?: "--:--",
                    meridiem = at?.let { WatchFormat.meridiem(context, it) },
                    line = message ?: at?.let { WatchFormat.countdown(now, it) } ?: "OFF",
                    small = shown?.let { WatchFormat.profile(it.profile) } ?: "",
                )
            }
            Mode.SLP -> {
                val sum = summary
                val bed = sum?.bedtimeEpochMs
                val wake = sum?.wakeEpochMs
                SleepBody(
                    s, palette,
                    total = sum?.totalSleepMin?.let(WatchFormat::hoursMinutes) ?: "-:--",
                    bedWake = if (bed != null && wake != null) "${WatchFormat.clock(context, bed)}-${WatchFormat.clock(context, wake)}" else null,
                    goal = sum?.let { "GOAL ${WatchFormat.hoursMinutes(it.goalMin)}" } ?: "",
                    fromHealthConnect = sum?.fromHealthConnect == true,
                    action = if (sleepAction == SleepAction.UP) "UP" else "BED",
                    message = message,
                )
            }
            Mode.PRO -> {
                val row = proRows(next).getOrNull(proRow)
                if (row == null) ProfileBody(s, palette, "PRO", "NO ALM", null, valueIsText = true, line = "", small = "")
                else ProfileBody(s, palette, row.title, row.value, row.prefix, row.valueIsText, row.line, row.small)
            }
            Mode.CHK -> CheckBody(
                s, palette,
                index = "${chkRow + 1}/${CheckItem.entries.size}",
                code = chkItem.code,
                ok = chkResult?.ok,
                blinkOn = blinkOn,
                detail = when {
                    chkResult == null -> "----"
                    testing == chkItem -> "HOLD STOP"
                    else -> chkResult.detail
                },
            )
        }
    }
}

/** The occurrence ALM shows for [channel]: its earliest open one, or null (disarmed, so absent). */
private fun entryFor(snapshot: ScheduleSnapshot?, channel: Int, now: Long): ScheduleEntry? =
    snapshot?.entries
        ?.filter { it.instance.alarmId == channel.toLong() && !it.instance.state.isTerminal && it.instance.scheduledAtEpochMs > now - 30 * 60_000 }
        ?.minByOrNull { it.instance.scheduledAtEpochMs }

private data class ProRow(
    val title: String,
    val value: String,
    val prefix: String? = null,
    val valueIsText: Boolean = false,
    val line: String,
    val small: String,
    val speech: String,
)

/** The next occurrence's wake profile as interval-timer rows: NAME, INT1..INT4, SIL. */
private fun proRows(entry: ScheduleEntry?): List<ProRow> {
    val profile = entry?.profile ?: return emptyList()
    val name = WatchFormat.profile(profile)
    fun stage(type: StageType) = profile.stages.firstOrNull { it.type == type }
    fun interval(title: String, label: String, seconds: Int?, speech: String, prefix: String? = null) = ProRow(
        title = title,
        value = seconds?.let(WatchFormat::minutesSeconds) ?: "--:--",
        prefix = prefix.takeIf { seconds != null },
        line = if (seconds == null) "$label OFF" else label,
        small = name,
        speech = if (seconds == null) "$speech: off" else "$speech: ${seconds / 60} minutes ${seconds % 60} seconds",
    )
    return listOf(
        ProRow("PRO", name, valueIsText = true, line = WatchFormat.channel(entry.instance.alarmId), small = "", speech = "Profile ${profile.name}"),
        interval("INT1", "LIGHT", stage(StageType.LIGHT)?.let { -it.offsetSec }, "Light, before the alarm"),
        interval("INT2", "BUZZ", stage(StageType.HAPTIC)?.let { -it.offsetSec }, "Vibration, before the alarm"),
        interval("INT3", "TONE", stage(StageType.SOUND)?.rampSec, "Sound ramp"),
        interval("INT4", "FULL", stage(StageType.ESCALATE)?.offsetSec, "Full volume, after the alarm", prefix = "+"),
        ProRow(
            "SIL", profile.autoSilenceMinutes.toString(), line = "MIN", small = name,
            speech = "Silences after ${profile.autoSilenceMinutes} minutes",
        ),
    )
}

private fun openSettings(context: Context, action: String) {
    val pkg = "package:${context.packageName}".toUri()
    runCatching { context.startActivity(Intent(action, pkg)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } }
}
