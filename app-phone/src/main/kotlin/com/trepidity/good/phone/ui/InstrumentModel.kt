package com.trepidity.good.phone.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trepidity.good.model.Alarm
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.StageType
import com.trepidity.good.model.Tone
import com.trepidity.good.model.WakeProfile
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.CheckId
import com.trepidity.good.phone.CheckItem
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.ReliabilityCheck
import com.trepidity.good.phone.alarm.AlarmScheduler
import com.trepidity.good.phone.alarm.ScheduleStore
import com.trepidity.good.phone.alarm.WakeUp
import com.trepidity.good.phone.data.SleepSessionEntity
import com.trepidity.good.phone.sleep.SleepApiReceiver
import com.trepidity.good.phone.sleep.SleepSyncWorker
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.phone.wake.WakeService
import com.trepidity.good.sleep.Night
import com.trepidity.good.sleep.NightWindow
import com.trepidity.good.sleep.SleepAction
import com.trepidity.good.sleep.SleepMetrics
import com.trepidity.good.wake.InstanceIds
import com.trepidity.good.wake.ProfileRow
import com.trepidity.good.wake.WakeBehaviour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class Mode { ALM, SLP, PRO, CHK }

/** ALM set sequence (SPEC UX, REVIEW U3): hour → minute → Mon…Sun → profile → tone → sound target. */
enum class AlmField(val code: String) {
    HOUR("HR"), MINUTE("MIN"), MO("MO"), TU("TU"), WE("WE"), TH("TH"), FR("FR"), SA("SA"), SU("SU"),
    PROFILE("PRO"), TONE("TONE"), TARGET("SND");

    /** Monday = 0 … Sunday = 6 for the day fields, else null. */
    val day: Int? get() = if (ordinal in MO.ordinal..SU.ordinal) ordinal - MO.ordinal else null
}

/** SLP edit sequence (REVIEW U5): the shown night's bed and wake times, then the goal and the reminder. */
enum class SlpField(val code: String) { BED("BED"), WAKE("WAKE"), GOAL("GOAL"), REMIND("REMIND") }

/** Rows after the CHK checks: a real test alarm, and the JSON export. */
enum class ChkAction(val code: String) { TST("TST"), EXP("EXP") }

data class SlpEdit(val wakeDate: LocalDate, val field: SlpField, val bed: Instant, val wake: Instant, val goalMin: Int, val remind: Boolean)

data class UiState(
    val mode: Mode = Mode.ALM,
    val channel: Long = 1,
    val almEdit: Pair<AlmField, Alarm>? = null,
    val lap: Int = 0,
    val slpEdit: SlpEdit? = null,
    val proProfile: Int = 0,
    val proRow: Int = 0,
    val proDraft: WakeProfile? = null,
    val chkIndex: Int = 0,
    val checks: List<CheckItem> = emptyList(),
    val checking: Boolean = false,
    val banner: String? = null,
    val glow: Boolean = false,
    val snapshot: ScheduleSnapshot? = null,
    val sleepGoalMin: Int = 450,
    val bedtimeReminder: Boolean = true,
    val sleepAction: SleepAction = SleepAction.BED,
)

/** Things only the Activity can do: open settings, ask for permissions, pick an export file. */
sealed interface Effect {
    data class Open(val intent: Intent) : Effect
    data object RequestHealthConnect : Effect
    data object RequestNotifications : Effect
    data object RequestActivityRecognition : Effect
    data object PickExportFile : Effect
}

/**
 * The instrument's state and the meaning of its five buttons in each mode. Every screen is the same LCD in a
 * different mode, so there is no navigation: only MODE (SPEC UX design).
 */
class InstrumentModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val alarmsRepo = AppGraph.alarms(app)
    private val sleepRepo = AppGraph.sleep(app)
    private val prefs = AppGraph.prefs(app)

    private val _state = MutableStateFlow(UiState(sleepGoalMin = prefs.sleepGoalMin, bedtimeReminder = prefs.bedtimeReminder))
    val state: StateFlow<UiState> = _state.asStateFlow()
    val effects = MutableSharedFlow<Effect>(extraBufferCapacity = 4)

    val alarms: StateFlow<List<Alarm>> = alarmsRepo.alarms.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val profiles: StateFlow<List<WakeProfile>> = alarmsRepo.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, WakeProfile.PRESETS)
    val sessions: StateFlow<List<SleepSessionEntity>> = sleepRepo.recent(30).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Metrics over the stored nights, recomputed whenever the sessions change. */
    val nights = MutableStateFlow<List<Night>>(emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            alarmsRepo.seed()
            refreshSnapshot(alarmsRepo.rescheduleAll("app open"))
            sleepRepo.syncRecent()
            SleepSyncWorker.scheduleDaily(ctx)
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessions.collect {
                nights.value = sleepRepo.nights()
                loadWakeLines()
            }
        }
        runChecks()
        refreshSleepAction()
    }

    fun onResume() {
        _state.update { it.copy(snapshot = ScheduleStore.load(ctx)) }
        runChecks()
        refreshSleepAction()
        refreshWakeLines()
    }

    // ---- The five case buttons --------------------------------------------------------------------------

    fun mode(forward: Boolean = true) {
        // While a field flashes, MODE only leaves set mode (without saving), like a real watch.
        if (_state.value.let { it.almEdit != null || it.slpEdit != null || it.proDraft != null }) {
            _state.update { it.copy(almEdit = null, slpEdit = null, proDraft = null) }
            return
        }
        _state.update {
            val modes = Mode.entries
            val next = modes[(it.mode.ordinal + if (forward) 1 else modes.size - 1) % modes.size]
            it.copy(mode = next)
        }
    }

    fun light() {
        _state.update { it.copy(glow = true) }
        viewModelScope.launch {
            delay(3_000)
            _state.update { it.copy(glow = false) }
        }
    }

    fun up() = step(+1)
    fun down() = step(-1)

    fun set() {
        val s = _state.value
        when (s.mode) {
            Mode.ALM -> almSet(s)
            Mode.SLP -> slpSet(s)
            Mode.PRO -> proSet(s)
            Mode.CHK -> chkSet(s)
        }
    }

    fun holdSet() {
        val s = _state.value
        when (s.mode) {
            Mode.ALM -> if (s.almEdit != null) saveAlarm(s.almEdit.second) else toggleArmed(s.channel)
            Mode.SLP -> if (s.slpEdit != null) saveSleepEdit(s.slpEdit) else startSleepEdit(s)
            Mode.PRO -> preview(currentProfile(s))
            Mode.CHK -> runChecks()
        }
    }

    // ---- ALM -------------------------------------------------------------------------------------------

    fun alarm(channel: Long): Alarm = alarms.value.firstOrNull { it.id == channel } ?: Alarm(id = channel, hour = 6, minute = 30, enabled = false)

    fun entryFor(channel: Long): ScheduleEntry? = _state.value.snapshot?.entries?.firstOrNull { it.instance.alarmId == channel && !it.instance.state.isTerminal }

    private fun almSet(s: UiState) {
        val edit = s.almEdit
        if (edit == null) {
            _state.update { it.copy(almEdit = AlmField.HOUR to alarm(s.channel)) }
            return
        }
        val nextField = AlmField.entries.getOrNull(edit.first.ordinal + 1)
        if (nextField == null) saveAlarm(edit.second) else _state.update { it.copy(almEdit = nextField to edit.second) }
    }

    private fun almStep(delta: Int, edit: Pair<AlmField, Alarm>) {
        val (field, a) = edit
        val profileIds = profiles.value.map { it.id }
        val next = when (field) {
            AlmField.HOUR -> a.copy(hour = Math.floorMod(a.hour + delta, 24))
            AlmField.MINUTE -> a.copy(minute = Math.floorMod(a.minute + delta, 60))
            AlmField.PROFILE -> a.copy(profileId = cycle(profileIds, a.profileId, delta))
            AlmField.TONE -> a.copy(tone = cycle(Tone.entries, a.tone, delta))
            AlmField.TARGET -> a.copy(soundTarget = cycle(SoundTarget.entries, a.soundTarget, delta))
            else -> a.copy(repeatDays = a.repeatDays xor (1 shl field.day!!))
        }
        _state.update { it.copy(almEdit = field to next) }
    }

    private fun saveAlarm(a: Alarm) {
        _state.update { it.copy(almEdit = null) }
        viewModelScope.launch(Dispatchers.IO) {
            alarmsRepo.save(a.copy(enabled = true, skipNextDate = null), "set AL${a.id}")
            refreshSnapshot()
            banner("ARMED")
        }
    }

    /** ALM, hold ▼ 2 s: skip the shown channel's next occurrence, or clear a pending skip (skip spec). */
    fun holdDown() {
        val s = _state.value
        if (s.mode != Mode.ALM || s.almEdit != null) return
        val a = alarm(s.channel)
        val pending = pendingSkip(a)
        val entry = entryFor(s.channel)
        val date = entry?.let { Instant.ofEpochMilli(it.instance.scheduledAtEpochMs).atZone(ZoneId.systemDefault()).toLocalDate() }
        when {
            pending != null -> skip(s.channel, null, "UNSKIP")
            a.repeatDays == 0 -> banner("ONCE USE OFF")
            !a.enabled || entry == null || date == null -> banner("OFF")
            entry.instance.state != InstanceState.SCHEDULED -> banner("RINGING")
            else -> skip(s.channel, date.toString(), "SKIPPED")
        }
    }

    private fun skip(channel: Long, date: String?, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            alarmsRepo.setSkip(channel, date)
            refreshSnapshot()
            loadWakeLines()
            banner(text)
        }
    }

    /** The pending skip date of [a], or null; an old date is ignored (NextOccurrence only compares future dates). */
    fun pendingSkip(a: Alarm, today: LocalDate = LocalDate.now()): LocalDate? =
        a.skipNextDate?.let(LocalDate::parse)?.takeIf { !it.isBefore(today) }

    private fun toggleArmed(channel: Long) {
        val armed = !alarm(channel).enabled
        viewModelScope.launch(Dispatchers.IO) {
            alarmsRepo.setArmed(channel, armed)
            refreshSnapshot()
            banner(if (armed) "ARMED" else "OFF")
        }
    }

    // ---- SLP -------------------------------------------------------------------------------------------

    private fun slpSet(s: UiState) {
        val edit = s.slpEdit
        if (edit == null) {
            val now = Instant.now()
            viewModelScope.launch(Dispatchers.IO) {
                if (sleepRepo.nextAction(now) == SleepAction.UP) {
                    WakeUp.record(ctx, now)
                    refreshSnapshot()
                    loadWakeLines()
                    banner("GOOD MORNING")
                } else {
                    prefs.lastBedtime = now.toEpochMilli()
                    sleepRepo.recordBedtime(now, "PHONE")
                    banner("GOOD NIGHT")
                }
                _state.update { it.copy(sleepAction = sleepRepo.nextAction()) }
            }
            return
        }
        val next = SlpField.entries.getOrNull(edit.field.ordinal + 1)
        if (next == null) saveSleepEdit(edit) else _state.update { it.copy(slpEdit = edit.copy(field = next)) }
    }

    private fun refreshSleepAction() {
        viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(sleepAction = sleepRepo.nextAction()) } }
    }

    /** Wake date (ISO) → the SLP lap's wake line ("WOKE SND +6", "UP EARLY", …) for the last 31 nights (#7). */
    val wakeLines = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun refreshWakeLines() {
        viewModelScope.launch(Dispatchers.IO) { loadWakeLines() }
    }

    private suspend fun loadWakeLines() {
        val zone = ZoneId.systemDefault()
        val since = Instant.now().minus(31, ChronoUnit.DAYS).toEpochMilli()
        wakeLines.value = AppGraph.db(ctx).instances().since(since).map { it.toModel() }
            .filter { it.alarmId > 0 }
            .groupBy { NightWindow.wakeDateOf(Instant.ofEpochMilli(it.scheduledAtEpochMs), zone)?.toString() }
            .mapNotNull { (date, list) ->
                val line = list.sortedBy { it.scheduledAtEpochMs }.firstNotNullOfOrNull { WakeBehaviour.of(it) }?.let(::wakeLine)
                if (date == null || line == null) null else date to line
            }.toMap()
    }

    private fun wakeLine(b: WakeBehaviour): String = when (b) {
        is WakeBehaviour.Dismissed -> "WOKE ${stageCode(b.stage)} +${b.minutesAfterFirstStage}"
        WakeBehaviour.UpEarly -> "UP EARLY"
        WakeBehaviour.Skipped -> "SKIPPED"
        WakeBehaviour.NoAnswer -> "NO ANSWER"
    }

    private fun stageCode(t: StageType) = when (t) {
        StageType.LIGHT -> "LIT"
        StageType.HAPTIC -> "BUZ"
        StageType.SOUND -> "SND"
        StageType.ESCALATE -> "MAX"
    }

    private fun startSleepEdit(s: UiState) {
        val session = sessions.value.getOrNull(s.lap)
        val zone = ZoneId.systemDefault()
        val date = session?.let { LocalDate.parse(it.wakeDate) } ?: LocalDate.now(zone).minusDays(s.lap.toLong())
        val bed = session?.let { Instant.ofEpochMilli(it.bedtimeAnchor ?: it.start) } ?: date.minusDays(1).atTime(23, 0).atZone(zone).toInstant()
        val wake = session?.let { Instant.ofEpochMilli(it.end) } ?: date.atTime(7, 0).atZone(zone).toInstant()
        _state.update { it.copy(slpEdit = SlpEdit(date, SlpField.BED, bed, wake, prefs.sleepGoalMin, prefs.bedtimeReminder)) }
    }

    private fun slpStep(delta: Int, e: SlpEdit) {
        val next = when (e.field) {
            SlpField.BED -> e.copy(bed = e.bed.plus(5L * delta, ChronoUnit.MINUTES))
            SlpField.WAKE -> e.copy(wake = e.wake.plus(5L * delta, ChronoUnit.MINUTES))
            SlpField.GOAL -> e.copy(goalMin = (e.goalMin + 15 * delta).coerceIn(240, 720))
            SlpField.REMIND -> e.copy(remind = !e.remind)
        }
        _state.update { it.copy(slpEdit = next) }
    }

    private fun saveSleepEdit(e: SlpEdit) {
        val session = sessions.value.firstOrNull { it.wakeDate == e.wakeDate.toString() }
        val timesChanged = session == null || Instant.ofEpochMilli(session.bedtimeAnchor ?: session.start) != e.bed || Instant.ofEpochMilli(session.end) != e.wake
        prefs.sleepGoalMin = e.goalMin
        prefs.bedtimeReminder = e.remind
        _state.update { it.copy(slpEdit = null, sleepGoalMin = e.goalMin, bedtimeReminder = e.remind) }
        viewModelScope.launch(Dispatchers.IO) {
            if (timesChanged) sleepRepo.edit(e.wakeDate, e.bed, e.wake)
            alarmsRepo.rescheduleAll("sleep settings") // moves the bedtime reminder
            sleepRepo.publishSummary()
            banner("SAVED")
        }
    }

    // ---- PRO -------------------------------------------------------------------------------------------

    fun currentProfile(s: UiState = _state.value): WakeProfile =
        s.proDraft ?: profiles.value.getOrNull(s.proProfile) ?: WakeProfile.GENTLE

    private fun proSet(s: UiState) {
        if (s.proDraft == null) {
            _state.update { it.copy(proDraft = currentProfile(it)) }
            return
        }
        val draft = s.proDraft
        _state.update { it.copy(proDraft = null) }
        if (s.proRow == 0) return // the profile row only picks which profile to show
        viewModelScope.launch(Dispatchers.IO) {
            alarmsRepo.saveProfile(draft)
            refreshSnapshot()
            banner("SAVED")
        }
    }

    private fun proStep(delta: Int, s: UiState) {
        val draft = s.proDraft
        if (draft == null) {
            // Lists read top to bottom: ▼ moves down a row, like CHK and LAP.
            _state.update { it.copy(proRow = Math.floorMod(it.proRow - delta, PRO_ROWS)) }
            return
        }
        if (s.proRow == 0) {
            val n = profiles.value.size.coerceAtLeast(1)
            val idx = Math.floorMod(s.proProfile + delta, n)
            _state.update { it.copy(proProfile = idx, proDraft = profiles.value.getOrNull(idx) ?: draft) }
            return
        }
        val row = ProfileRow.entries[s.proRow - 1]
        _state.update { it.copy(proDraft = row.set(draft, row.get(draft) + delta)) }
    }

    /** The 60-second preview of a profile on the phone (M4). */
    fun preview(profile: WakeProfile) {
        val fireAt = Instant.now().plusSeconds(30)
        val entry = ScheduleEntry(
            AlarmInstance(InstanceIds.of(PREVIEW_CHANNEL, fireAt), PREVIEW_CHANNEL, fireAt.toEpochMilli()), profile, SoundTarget.PHONE, profile.name,
        )
        val snap = ScheduleStore.load(ctx) ?: ScheduleSnapshot(0, 0, emptyList())
        ScheduleStore.save(ctx, snap.copy(entries = snap.entries.filterNot { it.instance.alarmId == PREVIEW_CHANNEL } + entry))
        ContextCompat.startForegroundService(ctx, WakeService.startIntent(ctx, entry.instance.id, preview = true))
        banner("PREVIEW")
    }

    // ---- CHK -------------------------------------------------------------------------------------------

    val chkCount: Int get() = _state.value.checks.size + ChkAction.entries.size

    fun runChecks() {
        _state.update { it.copy(checking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val items = ReliabilityCheck.run(ctx) + activityCheck()
            _state.update { it.copy(checks = items, checking = false) }
        }
    }

    private fun activityCheck() = CheckItem(
        CheckId.ACT, SleepApiReceiver.hasPermission(ctx), "phone sleep signals", null,
    )

    private fun chkSet(s: UiState) {
        val item = s.checks.getOrNull(s.chkIndex)
        if (item != null) {
            val effect = when (item.id) {
                CheckId.HC -> Effect.RequestHealthConnect
                CheckId.NTF -> if (!item.ok) Effect.RequestNotifications else item.fix?.let(Effect::Open)
                CheckId.ACT -> Effect.RequestActivityRecognition
                CheckId.LINK -> null.also { runChecks() }
                else -> item.fix?.let(Effect::Open)
            }
            effect?.let { effects.tryEmit(it) }
            return
        }
        when (ChkAction.entries.getOrNull(s.chkIndex - s.checks.size)) {
            ChkAction.TST -> testAlarm()
            ChkAction.EXP -> effects.tryEmit(Effect.PickExportFile)
            null -> Unit
        }
    }

    /** CHK → TST: a real alarm at +3 min on both devices, Quick profile, through the same path as AL1–AL4. */
    private fun testAlarm() {
        val fireAt = Instant.now().plus(3, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
        val profile = profiles.value.firstOrNull { it.id == WakeProfile.QUICK.id } ?: WakeProfile.QUICK
        val entry = ScheduleEntry(AlarmInstance(InstanceIds.of(0, fireAt), 0, fireAt.toEpochMilli()), profile, SoundTarget.AUTO, "TEST")
        viewModelScope.launch(Dispatchers.IO) {
            val snap = ScheduleStore.load(ctx) ?: ScheduleSnapshot(0, 0, emptyList())
            val next = ScheduleSnapshot(ScheduleStore.nextVersion(ctx), System.currentTimeMillis(), snap.entries.filterNot { it.instance.alarmId <= 0L } + entry)
            ScheduleStore.save(ctx, next)
            val ok = AlarmScheduler.schedule(ctx, entry)
            val pushed = runCatching { PhoneSync.pushSchedule(ctx, next) }.isSuccess
            EventLog.log(ctx, "TEST_ALARM", "at $fireAt phone=$ok watch=$pushed")
            banner(if (ok) "TEST 3:00" else "REFUSED")
        }
    }

    fun export(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val n = runCatching { com.trepidity.good.phone.DataExport.write(ctx, uri) }.getOrNull()
            banner(if (n == null) "EXP FAIL" else "EXP $n")
        }
    }

    // ---- Shared ----------------------------------------------------------------------------------------

    private fun step(delta: Int) {
        val s = _state.value
        when (s.mode) {
            Mode.ALM -> s.almEdit?.let { almStep(delta, it) }
                ?: _state.update { it.copy(channel = 1 + Math.floorMod(it.channel - 1 + delta, 4).toLong()) }
            Mode.SLP -> s.slpEdit?.let { slpStep(delta, it) }
                ?: _state.update { it.copy(lap = (it.lap - delta).coerceIn(0, 29)) }
            Mode.PRO -> proStep(delta, s)
            Mode.CHK -> _state.update { it.copy(chkIndex = Math.floorMod(it.chkIndex - delta, chkCount.coerceAtLeast(1))) }
        }
    }

    private fun refreshSnapshot(snapshot: ScheduleSnapshot? = null) =
        _state.update { it.copy(snapshot = snapshot ?: ScheduleStore.load(ctx)) }

    private fun banner(text: String) {
        _state.update { it.copy(banner = text) }
        viewModelScope.launch {
            delay(2_000)
            _state.update { if (it.banner == text) it.copy(banner = null) else it }
        }
    }

    /** "IN 7:20" for the next occurrence of [entry], or null when there is none. */
    fun countdown(entry: ScheduleEntry?, now: Instant): String? {
        entry ?: return null
        val mins = Duration.between(now, Instant.ofEpochMilli(entry.instance.scheduledAtEpochMs)).toMinutes()
        return if (mins < 0) "NOW" else "IN ${mins / 60}:${"%02d".format(mins % 60)}"
    }

    fun sleepAverages(today: LocalDate = LocalDate.now()): Triple<Int?, Int?, Int> {
        val n = nights.value
        return Triple(SleepMetrics.averageSleepMin(n, 7, today), SleepMetrics.averageSleepMin(n, 30, today), SleepMetrics.sleepDebtMin(n, prefs.sleepGoalMin, today))
    }

    fun bedtimeSpread(): Int? = SleepMetrics.bedtimeSpreadMin(nights.value.takeLast(14).map { it.bedtime }, ZoneId.systemDefault())

    companion object {
        /** NAME + the five ProfileRow rows. */
        const val PRO_ROWS = 6

        /** Previews live only in the phone's snapshot: never pushed to the watch, never in Room. */
        const val PREVIEW_CHANNEL = -1L

        private fun <T> cycle(values: List<T>, current: T, delta: Int): T {
            if (values.isEmpty()) return current
            val i = values.indexOf(current).coerceAtLeast(0)
            return values[Math.floorMod(i + delta, values.size)]
        }

        fun hhmm(t: LocalTime) = "${t.hour}:${"%02d".format(t.minute)}"
    }
}
