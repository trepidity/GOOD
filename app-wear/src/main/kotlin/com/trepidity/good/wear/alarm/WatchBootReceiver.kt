package com.trepidity.good.wear.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trepidity.good.wear.sleep.PassiveSleep

/** Locked or unlocked boot, and app update: re-register from the device-protected snapshot (REVIEW R1). */
class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchScheduleStore.load(context)?.let { WatchAlarmScheduler.apply(context, it) }
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) PassiveSleep.register(context)
    }
}
