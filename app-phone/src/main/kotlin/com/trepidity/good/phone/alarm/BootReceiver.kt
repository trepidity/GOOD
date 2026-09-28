package com.trepidity.good.phone.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.GoodApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Boot (locked or unlocked), app update, clock and time-zone changes. Before first unlock only the device-protected
 * snapshot is readable, so its pending occurrences are re-registered as they are (REVIEW R1); once unlocked,
 * everything funnels into the idempotent rescheduleAll().
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        registerFromSnapshot(context)
        if (!AppGraph.isUnlocked(context)) {
            EventLog.log(context, "BOOT_LOCKED", "re-registered from device-protected snapshot")
            return
        }
        val pending = goAsync()
        (context.applicationContext as GoodApplication).appScope.launch {
            try {
                withTimeoutOrNull(9_000) { AppGraph.alarms(context).rescheduleAll(intent.action ?: "boot") }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun registerFromSnapshot(context: Context) {
            val now = System.currentTimeMillis()
            ScheduleStore.load(context)?.entries
                ?.filter {
                    it.instance.alarmId >= 0 && !it.instance.state.isTerminal &&
                        it.instance.scheduledAtEpochMs > now - it.profile.autoSilenceMinutes * 60_000L
                }
                ?.forEach { AlarmScheduler.schedule(context, it) }
        }
    }
}
