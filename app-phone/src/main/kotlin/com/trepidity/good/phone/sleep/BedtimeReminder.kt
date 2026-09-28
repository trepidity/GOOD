package com.trepidity.good.phone.sleep

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.GoodApplication
import com.trepidity.good.phone.MainActivity
import com.trepidity.good.sleep.BedtimeReminder
import java.time.Instant

/** F9: a nudge at next alarm − sleep goal − 15 min. Inexact is fine for a reminder, so no exact alarm is spent on it. */
object BedtimeReminderScheduler {

    fun update(context: Context, snapshot: ScheduleSnapshot) {
        val prefs = AppGraph.prefs(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        am.cancel(pi)
        if (!prefs.bedtimeReminder) return
        val next = snapshot.entries
            .filter { it.instance.alarmId > 0 && it.instance.state == InstanceState.SCHEDULED }
            .minByOrNull { it.instance.scheduledAtEpochMs } ?: return
        val at = BedtimeReminder.at(Instant.ofEpochMilli(next.instance.scheduledAtEpochMs), prefs.sleepGoalMin)
        if (at.isAfter(Instant.now())) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 100, Intent(context, BedtimeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class BedtimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val goal = AppGraph.prefs(context).sleepGoalMin
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, GoodApplication.CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Bedtime in 15 min")
            .setContentText("For ${goal / 60}:${"%02d".format(goal % 60)} of sleep before your alarm.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    private companion object {
        const val NOTIFICATION_ID = 45
    }
}
