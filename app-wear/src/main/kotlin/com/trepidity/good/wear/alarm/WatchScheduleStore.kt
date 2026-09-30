package com.trepidity.good.wear.alarm

import android.content.Context
import androidx.core.content.edit
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.SleepSummary
import com.trepidity.good.sleep.SleepAction
import com.trepidity.good.sleep.SleepToggle
import com.trepidity.good.sync.SyncCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId

/**
 * The watch's own copy of the schedule (so it rings with the phone out of range) and the last sleep summary,
 * in device-protected storage so a locked reboot still rings (REVIEW R1).
 */
object WatchScheduleStore {
    private const val PREFS = "good_watch_schedule"
    private const val KEY = "snapshot"
    private const val KEY_SUMMARY = "sleepSummary"
    private const val KEY_BED = "localBedAt"
    private const val KEY_WAKE = "localWakeAt"
    private val lock = Any()

    private val _snapshot = MutableStateFlow<ScheduleSnapshot?>(null)
    private val _summary = MutableStateFlow<SleepSummary?>(null)

    /** For the watch UI, tile and complications. Call [load]/[summary] once to prime them. */
    val snapshot: StateFlow<ScheduleSnapshot?> = _snapshot
    val sleepSummary: StateFlow<SleepSummary?> = _summary

    fun load(context: Context): ScheduleSnapshot? =
        prefs(context).getString(KEY, null)
            ?.let { runCatching { SyncCodec.decodeSchedule(it.encodeToByteArray()) }.getOrNull() }
            .also { _snapshot.value = it }

    fun save(context: Context, snapshot: ScheduleSnapshot) = synchronized(lock) {
        prefs(context).edit(commit = true) { putString(KEY, SyncCodec.encode(snapshot).decodeToString()) }
        _snapshot.value = snapshot
    }

    fun find(context: Context, instanceId: String): ScheduleEntry? =
        load(context)?.entries?.firstOrNull { it.instance.id == instanceId }

    fun update(context: Context, instance: AlarmInstance) = synchronized(lock) {
        val snap = load(context) ?: return
        save(context, snap.copy(entries = snap.entries.map { if (it.instance.id == instance.id) it.copy(instance = instance) else it }))
    }

    /** The next pending occurrence, for the tile, complication and ALM mode. */
    fun next(context: Context): ScheduleEntry? =
        (snapshot.value ?: load(context))?.entries
            ?.filter { !it.instance.state.isTerminal && it.instance.scheduledAtEpochMs > System.currentTimeMillis() - 30 * 60_000 }
            ?.minByOrNull { it.instance.scheduledAtEpochMs }

    fun summary(context: Context): SleepSummary? =
        prefs(context).getString(KEY_SUMMARY, null)
            ?.let { runCatching { SyncCodec.decodeAny<SleepSummary>(it.encodeToByteArray()) }.getOrNull() }
            .also { _summary.value = it }

    fun saveSummary(context: Context, summary: SleepSummary) {
        prefs(context).edit(commit = true) { putString(KEY_SUMMARY, SyncCodec.encodeAny(summary).decodeToString()) }
        _summary.value = summary
    }

    /** Presses made on the watch, so the BED/UP toggle flips at once even with the phone out of range. */
    fun recordLocalBed(context: Context, at: Long) = prefs(context).edit(commit = true) { putLong(KEY_BED, at) }
    fun recordLocalWake(context: Context, at: Long) = prefs(context).edit(commit = true) { putLong(KEY_WAKE, at) }

    /** BED or UP: the later of the phone's and the watch's own anchors of each kind (I'M UP spec). */
    fun sleepAction(context: Context, now: Instant = Instant.now()): SleepAction {
        val p = prefs(context)
        val summary = sleepSummary.value ?: summary(context)
        val bed = listOfNotNull(summary?.lastBedAnchorEpochMs, p.getLong(KEY_BED, 0).takeIf { it > 0 }).maxOrNull()
        val wake = listOfNotNull(summary?.lastWakeAnchorEpochMs, p.getLong(KEY_WAKE, 0).takeIf { it > 0 }).maxOrNull()
        return SleepToggle.next(bed?.let(Instant::ofEpochMilli), wake?.let(Instant::ofEpochMilli), now, ZoneId.systemDefault())
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
