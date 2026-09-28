package com.trepidity.good.wear.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trepidity.good.model.Device
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.wake.WakePlanner
import com.trepidity.good.wear.MainActivity
import java.time.Instant

/** setAlarmClock() at the watch's first stage (haptics, T−3) plus a backup at T. */
object WatchAlarmScheduler {
    private const val TAG = "GoodWatchScheduler"

    fun canScheduleExact(context: Context) = alarmManager(context).canScheduleExactAlarms()

    fun schedule(context: Context, entry: ScheduleEntry): Boolean {
        if (entry.instance.state.isTerminal) return false
        val fireAt = Instant.ofEpochMilli(entry.instance.scheduledAtEpochMs)
        val plan = WakePlanner.plan(fireAt, entry.profile, Device.WATCH, entry.soundTarget, watchAvailable = true)
        val first = maxOf(WakePlanner.firstStageAt(plan) ?: fireAt, Instant.now().plusSeconds(5))
        return try {
            setClock(context, first, code(entry.instance.id, 0), entry.instance.id)
            if (fireAt.isAfter(first)) setClock(context, fireAt, code(entry.instance.id, 1), entry.instance.id)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused", e)
            false
        }
    }

    /** Replace everything scheduled from [old] with [new]. */
    fun apply(context: Context, old: ScheduleSnapshot?, new: ScheduleSnapshot) {
        old?.entries?.forEach { cancel(context, it.instance.id) }
        val now = System.currentTimeMillis()
        new.entries.filter { it.instance.scheduledAtEpochMs > now }.forEach { schedule(context, it) }
    }

    fun cancel(context: Context, instanceId: String) {
        for (slot in 0..1) {
            PendingIntent.getBroadcast(
                context, code(instanceId, slot), intent(context, instanceId),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { alarmManager(context).cancel(it) }
        }
    }

    private fun setClock(context: Context, at: Instant, code: Int, instanceId: String) {
        val op = PendingIntent.getBroadcast(context, code, intent(context, instanceId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager(context).setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), op)
        Log.i(TAG, "setAlarmClock $instanceId at $at")
    }

    private fun intent(context: Context, instanceId: String) =
        Intent(context, WatchAlarmReceiver::class.java).putExtra(WatchAlarmReceiver.EXTRA_INSTANCE_ID, instanceId)

    private fun code(instanceId: String, slot: Int) = instanceId.hashCode() * 2 + slot

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)
}
