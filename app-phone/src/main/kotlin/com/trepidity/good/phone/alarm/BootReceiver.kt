package com.trepidity.good.phone.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot, app update, clock and time-zone changes all funnel into one idempotent rescheduleAll(). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        rescheduleAll(context)
    }

    companion object {
        fun rescheduleAll(context: Context) {
            // M0: re-register what's in the snapshot. M1: recompute next occurrences from Room
            // with NextOccurrence.nextFireTime() so time-zone changes move wall-clock alarms.
            val now = System.currentTimeMillis()
            ScheduleStore.load(context)?.entries
                ?.filter { !it.instance.state.isTerminal && it.instance.scheduledAtEpochMs > now - 20 * 60_000 }
                ?.forEach { AlarmScheduler.schedule(context, it) }
        }
    }
}
