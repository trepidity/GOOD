package com.trepidity.good.phone.alarm

import android.content.Context
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.sync.SyncCodec

/**
 * M0 stand-in for the Room database: keeps the current schedule snapshot in SharedPreferences.
 * Replaced by :core:data in M1.
 */
object ScheduleStore {
    private const val PREFS = "good_schedule"
    private const val KEY = "snapshot"

    fun load(context: Context): ScheduleSnapshot? =
        prefs(context).getString(KEY, null)?.let { runCatching { SyncCodec.decodeSchedule(it.encodeToByteArray()) }.getOrNull() }

    fun save(context: Context, snapshot: ScheduleSnapshot) {
        prefs(context).edit().putString(KEY, SyncCodec.encode(snapshot).decodeToString()).apply()
    }

    fun find(context: Context, instanceId: String): ScheduleEntry? =
        load(context)?.entries?.firstOrNull { it.instance.id == instanceId }

    fun update(context: Context, instance: AlarmInstance) {
        val snap = load(context) ?: return
        save(context, snap.copy(entries = snap.entries.map { if (it.instance.id == instance.id) it.copy(instance = instance) else it }))
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
