package com.trepidity.good.phone.alarm

import android.content.Context
import androidx.core.content.edit
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.sync.SyncCodec

/**
 * The published schedule, in device-protected storage so the alarm can be re-registered and ring after a
 * reboot before the phone is unlocked (REVIEW R1). Room holds the source of truth; this is what rings.
 */
object ScheduleStore {
    private const val PREFS = "good_schedule"
    private const val KEY = "snapshot"
    private val lock = Any()

    fun load(context: Context): ScheduleSnapshot? =
        prefs(context).getString(KEY, null)?.let { runCatching { SyncCodec.decodeSchedule(it.encodeToByteArray()) }.getOrNull() }

    fun save(context: Context, snapshot: ScheduleSnapshot) = synchronized(lock) {
        prefs(context).edit(commit = true) { putString(KEY, SyncCodec.encode(snapshot).decodeToString()) }
    }

    fun find(context: Context, instanceId: String): ScheduleEntry? =
        load(context)?.entries?.firstOrNull { it.instance.id == instanceId }

    /** Records a state change of one occurrence; returns false if it isn't in the snapshot. */
    fun update(context: Context, instance: AlarmInstance): Boolean = synchronized(lock) {
        val snap = load(context) ?: return false
        if (snap.entries.none { it.instance.id == instance.id }) return false
        save(context, snap.copy(entries = snap.entries.map { if (it.instance.id == instance.id) it.copy(instance = instance) else it }))
        true
    }

    /** Monotonic version for the next snapshot pushed to the watch. */
    fun nextVersion(context: Context): Long =
        maxOf(System.currentTimeMillis(), (load(context)?.version ?: 0L) + 1)

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
