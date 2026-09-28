package com.trepidity.good.phone.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.trepidity.good.model.Device
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.phone.MainActivity
import com.trepidity.good.wake.WakePlanner
import java.time.Instant

/**
 * Registers each alarm instance with setAlarmClock() twice: once at the phone's earliest possible stage
 * (so the light ramp can start) and once at T as a backup. See docs/SPEC.md, Reliability rules.
 */
object AlarmScheduler {
    private const val TAG = "GoodAlarmScheduler"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    /** Returns false if the OS refused (exact-alarm permission revoked). */
    fun schedule(context: Context, entry: ScheduleEntry): Boolean {
        if (entry.instance.state.isTerminal) return false
        val fireAt = Instant.ofEpochMilli(entry.instance.scheduledAtEpochMs)
        // Plan as if the watch were missing: that is the phone's earliest possible start.
        val plan = WakePlanner.plan(fireAt, entry.profile, Device.PHONE, entry.soundTarget, watchAvailable = false)
        val soonest = Instant.now().plusSeconds(5)
        val first = maxOf(WakePlanner.firstStageAt(plan) ?: fireAt, soonest)
        return try {
            setClock(context, first, requestCode(entry.instance.id, 0), entry.instance.id)
            if (fireAt.isAfter(first)) setClock(context, fireAt, requestCode(entry.instance.id, 1), entry.instance.id)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused", e)
            false
        }
    }

    fun cancel(context: Context, instanceId: String) {
        val am = alarmManager(context)
        for (slot in 0..1) {
            PendingIntent.getBroadcast(
                context, requestCode(instanceId, slot), receiverIntent(context, instanceId),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { am.cancel(it) }
        }
    }

    private fun setClock(context: Context, at: Instant, code: Int, instanceId: String) {
        val operation = PendingIntent.getBroadcast(
            context, code, receiverIntent(context, instanceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager(context).setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
        Log.i(TAG, "setAlarmClock $instanceId at $at")
    }

    private fun receiverIntent(context: Context, instanceId: String) =
        Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_INSTANCE_ID, instanceId)

    private fun requestCode(instanceId: String, slot: Int) = instanceId.hashCode() * 2 + slot

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)
}
