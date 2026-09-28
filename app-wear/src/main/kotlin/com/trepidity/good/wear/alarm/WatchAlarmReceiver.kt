package com.trepidity.good.wear.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.trepidity.good.wear.wake.WakeStageService

class WatchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return
        ContextCompat.startForegroundService(context, WakeStageService.startIntent(context, id))
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instanceId"
    }
}
