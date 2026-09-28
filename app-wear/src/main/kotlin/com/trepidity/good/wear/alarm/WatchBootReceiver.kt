package com.trepidity.good.wear.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WatchScheduleStore.load(context)?.let { WatchAlarmScheduler.apply(context, null, it) }
    }
}
