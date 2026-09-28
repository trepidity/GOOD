package com.trepidity.good.phone.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.trepidity.good.phone.wake.WakeService

/** Fired by setAlarmClock(); an exact alarm lets us start a foreground service from the background. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return
        ContextCompat.startForegroundService(context, WakeService.startIntent(context, id))
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instanceId"
    }
}
