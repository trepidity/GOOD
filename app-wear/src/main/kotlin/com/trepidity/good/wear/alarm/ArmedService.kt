package com.trepidity.good.wear.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trepidity.good.wear.MainActivity
import com.trepidity.good.wear.WearApplication
import java.time.Instant
import java.time.ZoneId

/**
 * Keeps GOOD out of the "empty process" state while an alarm is armed. The 2R's framework force-stops idle
 * third-party apps within seconds to minutes, and a force stop erases their alarms, even when allowlisted
 * (verified on the watch, REVIEW F1). A foreground service with a silent "armed" notice ranks far above an
 * empty process and does no work while idle.
 */
class ArmedService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val next = WatchScheduleStore.next(this)
        val text = next?.let {
            val t = Instant.ofEpochMilli(it.instance.scheduledAtEpochMs).atZone(ZoneId.systemDefault()).toLocalTime()
            "AL${it.instance.alarmId} ${t.hour}:${"%02d".format(t.minute)}"
        } ?: "Armed"
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, WearApplication.CHANNEL_ARMED)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("GOOD")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        if (next == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 46

        /** Runs the service while [armed], stops it otherwise. Safe to call from any scheduling path. */
        fun sync(context: Context, armed: Boolean) {
            val intent = Intent(context, ArmedService::class.java)
            if (armed) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
                    .onFailure { Log.w("GoodArmed", "Could not start the armed service", it) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
