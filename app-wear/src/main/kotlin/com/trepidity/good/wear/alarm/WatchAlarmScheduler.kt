package com.trepidity.good.wear.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.wake.WakePlanner
import com.trepidity.good.wear.MainActivity
import java.time.Instant

/** setAlarmClock() at the watch's first stage (haptics, T−3) plus a backup at T. Request codes are per channel. */
object WatchAlarmScheduler {
    private const val TAG = "GoodWatchScheduler"
    private val CHANNELS = 0L..4L

    fun canScheduleExact(context: Context) = alarmManager(context).canScheduleExactAlarms()

    fun schedule(context: Context, entry: ScheduleEntry): Boolean {
        if (entry.instance.state != InstanceState.SCHEDULED) return false
        val fireAt = Instant.ofEpochMilli(entry.instance.scheduledAtEpochMs)
        val plan = WakePlanner.plan(fireAt, entry.profile, Device.WATCH, entry.soundTarget, watchAvailable = true)
        val first = maxOf(WakePlanner.firstStageAt(plan) ?: fireAt, Instant.now().plusSeconds(5))
        val channel = entry.instance.alarmId
        return try {
            setClock(context, first, code(channel, 0), entry.instance.id)
            if (fireAt.isAfter(first)) setClock(context, fireAt, code(channel, 1), entry.instance.id) else cancelSlot(context, channel, 1)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused", e)
            false
        }
    }

    /** Registers exactly what [snapshot] says is pending; every other channel is cleared. */
    fun apply(context: Context, snapshot: ScheduleSnapshot) {
        val now = System.currentTimeMillis()
        val pending = snapshot.entries
            .filter { it.instance.state == InstanceState.SCHEDULED && it.instance.scheduledAtEpochMs > now - it.profile.autoSilenceMinutes * 60_000L }
            .associateBy { it.instance.alarmId }
        for (channel in CHANNELS) {
            val e = pending[channel]
            if (e == null) cancelChannel(context, channel) else schedule(context, e)
        }
        ArmedService.sync(context, pending.isNotEmpty())
    }

    fun cancelChannel(context: Context, channel: Long) {
        cancelSlot(context, channel, 0)
        cancelSlot(context, channel, 1)
    }

    private fun cancelSlot(context: Context, channel: Long, slot: Int) {
        PendingIntent.getBroadcast(
            context, code(channel, slot), Intent(context, WatchAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            alarmManager(context).cancel(it)
            it.cancel()
        }
    }

    private fun setClock(context: Context, at: Instant, code: Int, instanceId: String) {
        val op = PendingIntent.getBroadcast(
            context, code, Intent(context, WatchAlarmReceiver::class.java).putExtra(WatchAlarmReceiver.EXTRA_INSTANCE_ID, instanceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager(context).setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), op)
        Log.i(TAG, "setAlarmClock $instanceId at $at")
    }

    private fun code(channel: Long, slot: Int) = (channel * 2 + slot).toInt()

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)
}
