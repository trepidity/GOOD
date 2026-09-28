package com.trepidity.good.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.trepidity.good.model.InstanceState
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.wake.WakeStageService

/** Woken by Play services when the phone publishes a schedule or sends a command. */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != DataLayerPaths.SCHEDULE) continue
            val bytes = DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(DataLayerPaths.KEY_PAYLOAD) ?: continue
            val incoming = runCatching { SyncCodec.decodeSchedule(bytes) }.getOrNull() ?: continue
            val current = WatchScheduleStore.load(this)
            if (!SyncCodec.shouldApply(incoming, current)) continue
            WatchScheduleStore.save(this, incoming)
            WatchAlarmScheduler.apply(this, current, incoming)
            Log.i(TAG, "Applied schedule v${incoming.version} (${incoming.entries.size} entries)")
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            DataLayerPaths.CMD_DISMISS -> {
                val cmd = runCatching { SyncCodec.decodeCommand(event.data) }.getOrNull() ?: return
                WakeStageService.remoteCommands.tryEmit(cmd)
                WatchScheduleStore.find(this, cmd.instanceId)?.let {
                    WatchScheduleStore.update(this, it.instance.copy(state = InstanceState.DISMISSED, dismissedAtEpochMs = cmd.sentAtEpochMs, dismissedOn = cmd.from))
                }
                WatchAlarmScheduler.cancel(this, cmd.instanceId)
            }
            DataLayerPaths.HEALTH -> {
                // M2: answer with the off-body sensor state. For now: reachable = worn.
                Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, DataLayerPaths.HEALTH_REPLY, byteArrayOf(1))
            }
        }
    }

    private companion object {
        const val TAG = "GoodWatchListener"
    }
}
