package com.trepidity.good.phone.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trepidity.good.model.Device
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.phone.MainActivity
import com.trepidity.good.wake.WakePlanner
import java.time.Instant

/**
 * Registers each occurrence with setAlarmClock() twice: at the phone's earliest possible stage (so the light
 * ramp can start) and at T as a backup. Request codes are per channel, so a channel's new occurrence replaces
 * its old one. See docs/SPEC.md, Reliability rules.
 */
object AlarmScheduler {
    private const val TAG = "GoodAlarmScheduler"

    fun canScheduleExact(context: Context): Boolean = alarmManager(context).canScheduleExactAlarms()

    /** Returns false if the OS refused (exact-alarm permission revoked). */
    fun schedule(context: Context, entry: ScheduleEntry): Boolean {
        if (entry.instance.state.isTerminal) return false
        val fireAt = Instant.ofEpochMilli(entry.instance.scheduledAtEpochMs)
        // Plan as if the watch were missing: that is the phone's earliest possible start.
        val plan = WakePlanner.plan(fireAt, entry.profile, Device.PHONE, entry.soundTarget, watchAvailable = false)
        val soonest = Instant.now().plusSeconds(5)
        val first = maxOf(WakePlanner.firstStageAt(plan) ?: fireAt, soonest)
        val channel = entry.instance.alarmId
        return try {
            setClock(context, first, requestCode(channel, 0), entry.instance.id)
            if (fireAt.isAfter(first)) setClock(context, fireAt, requestCode(channel, 1), entry.instance.id)
            else cancelSlot(context, channel, 1)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused", e)
            false
        }
    }

    fun cancelChannel(context: Context, channel: Long) {
        cancelSlot(context, channel, 0)
        cancelSlot(context, channel, 1)
    }

    private fun cancelSlot(context: Context, channel: Long, slot: Int) {
        PendingIntent.getBroadcast(
            context, requestCode(channel, slot), Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            alarmManager(context).cancel(it)
            it.cancel()
        }
    }

    private fun setClock(context: Context, at: Instant, code: Int, instanceId: String) {
        val operation = PendingIntent.getBroadcast(
            context, code,
            Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_INSTANCE_ID, instanceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager(context).setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
        Log.i(TAG, "setAlarmClock $instanceId at $at")
    }

    /** Channel 0 is the CHK test alarm; 1–4 are AL1–AL4. */
    private fun requestCode(channel: Long, slot: Int) = (channel * 2 + slot).toInt()

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)
}
