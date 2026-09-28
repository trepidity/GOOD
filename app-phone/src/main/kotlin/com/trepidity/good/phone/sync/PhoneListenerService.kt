package com.trepidity.good.phone.sync

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.trepidity.good.model.InstanceState
import com.trepidity.good.phone.alarm.AlarmScheduler
import com.trepidity.good.phone.alarm.ScheduleStore
import com.trepidity.good.phone.wake.WakeService
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec

/** Receives dismiss and bedtime from the watch. Woken by Play services even when GOOD isn't running. */
class PhoneListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        Log.i(TAG, "Message ${event.path}")
        when (event.path) {
            DataLayerPaths.CMD_DISMISS -> {
                val cmd = runCatching { SyncCodec.decodeCommand(event.data) }.getOrNull() ?: return
                // If the ring service is running it stops itself; either way the store and alarms are updated.
                WakeService.remoteCommands.tryEmit(cmd)
                val entry = ScheduleStore.find(this, cmd.instanceId) ?: return
                ScheduleStore.update(
                    this,
                    entry.instance.copy(state = InstanceState.DISMISSED, dismissedAtEpochMs = cmd.sentAtEpochMs, dismissedOn = cmd.from),
                )
                AlarmScheduler.cancel(this, cmd.instanceId)
            }
            DataLayerPaths.SLEEP_BEDTIME -> {
                // M3: record a bedtime anchor for the sleep-session builder.
                getSharedPreferences("good_sleep", MODE_PRIVATE).edit()
                    .putLong("lastBedtime", System.currentTimeMillis()).apply()
            }
            DataLayerPaths.HEALTH_REPLY -> {
                // M2: record worn / reachable state for the T−15 ping.
            }
        }
    }

    private companion object {
        const val TAG = "GoodPhoneListener"
    }
}
