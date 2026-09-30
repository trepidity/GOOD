package com.trepidity.good.phone.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.trepidity.good.model.BedtimeMessage
import com.trepidity.good.model.Command
import com.trepidity.good.model.HealthReply
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.SleepSignalMessage
import com.trepidity.good.model.ToggleCommand
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.GoodApplication
import com.trepidity.good.phone.alarm.InstanceEvents
import com.trepidity.good.phone.alarm.ScheduleStore
import com.trepidity.good.phone.wake.WakeService
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.sync.instanceIdFromStatePath
import kotlinx.coroutines.launch
import java.time.Instant

/** Receives dismiss, toggles, bedtime, sleep signals and health replies from the watch. Woken by Play services. */
class PhoneListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        Log.i(TAG, "Message ${event.path}")
        when (event.path) {
            DataLayerPaths.CMD_DISMISS -> runCatching { SyncCodec.decodeCommand(event.data) }.getOrNull()?.let(::remoteClose)
            DataLayerPaths.HEALTH_REPLY -> runCatching { SyncCodec.decodeAny<HealthReply>(event.data) }.getOrNull()
                ?.let { PhoneSync.healthReplies.tryEmit(it) }
            DataLayerPaths.CMD_TOGGLE -> runCatching { SyncCodec.decodeAny<ToggleCommand>(event.data) }.getOrNull()?.let { cmd ->
                background { AppGraph.alarms(this).toggle(cmd.alarmId) }
            }
            DataLayerPaths.SLEEP_BEDTIME -> runCatching { SyncCodec.decodeAny<BedtimeMessage>(event.data) }.getOrNull()?.let { msg ->
                background { AppGraph.sleep(this).recordBedtime(Instant.ofEpochMilli(msg.atEpochMs), "WATCH") }
            }
            DataLayerPaths.SLEEP_SIGNAL -> runCatching { SyncCodec.decodeAny<SleepSignalMessage>(event.data) }.getOrNull()?.let { msg ->
                background { AppGraph.sleep(this).recordWatchSignal(Instant.ofEpochMilli(msg.atEpochMs), msg.asleep) }
            }
        }
    }

    /** The watch's `/instance/{id}/state` item: catch-up for a dismiss message that was missed. */
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            instanceIdFromStatePath(event.dataItem.uri.path) ?: continue
            val bytes = DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(DataLayerPaths.KEY_PAYLOAD) ?: continue
            val cmd = runCatching { SyncCodec.decodeCommand(bytes) }.getOrNull() ?: continue
            if (cmd.from == com.trepidity.good.model.Device.WATCH) remoteClose(cmd)
        }
    }

    /** Idempotent: a close (dismiss or cancel) for an occurrence that is already over changes nothing. */
    private fun remoteClose(cmd: Command) {
        WakeService.remoteCommands.tryEmit(cmd)
        val entry = ScheduleStore.find(this, cmd.instanceId) ?: return
        if (entry.instance.state.isTerminal) return
        EventLog.log(this, "DISMISS_REMOTE", "${cmd.instanceId} from ${cmd.from} as ${cmd.state} after ${System.currentTimeMillis() - cmd.sentAtEpochMs} ms")
        val closed = if (cmd.state == InstanceState.DISMISSED) {
            entry.instance.copy(
                state = InstanceState.DISMISSED, currentStage = null, dismissedAtStage = entry.instance.currentStage,
                dismissedAtEpochMs = cmd.sentAtEpochMs, dismissedOn = cmd.from,
            )
        } else {
            entry.instance.copy(state = cmd.state, currentStage = null)
        }
        InstanceEvents.record(this, closed)
    }

    private fun background(block: suspend () -> Unit) {
        if (!AppGraph.isUnlocked(this)) return
        (application as GoodApplication).appScope.launch { runCatching { block() }.onFailure { Log.w(TAG, "Handler failed", it) } }
    }

    private companion object {
        const val TAG = "GoodPhoneListener"
    }
}
