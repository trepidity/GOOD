package com.trepidity.good.wear.sync

import android.util.Log
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.trepidity.good.model.Command
import com.trepidity.good.model.Device
import com.trepidity.good.model.HealthReply
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.SleepSummary
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.ScheduleMerge
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.sync.instanceIdFromStatePath
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.surface.WatchSurfaces
import com.trepidity.good.wear.wake.WakeStageService
import com.trepidity.good.wear.wake.WornSensor
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Woken by Play services when the phone publishes a schedule, a sleep summary or a dismiss, or pings `/health`. */
class WatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path
            val bytes = DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(DataLayerPaths.KEY_PAYLOAD) ?: continue
            when {
                path == DataLayerPaths.SCHEDULE -> applySchedule(bytes)
                path == DataLayerPaths.SLEEP_SUMMARY -> runCatching { SyncCodec.decodeAny<SleepSummary>(bytes) }.getOrNull()?.let {
                    WatchScheduleStore.saveSummary(this, it)
                    WatchSurfaces.refresh(this)
                }
                instanceIdFromStatePath(path) != null -> runCatching { SyncCodec.decodeCommand(bytes) }.getOrNull()
                    ?.takeIf { it.from == Device.PHONE }?.let(::remoteClose)
            }
        }
    }

    private fun applySchedule(bytes: ByteArray) {
        val incoming = runCatching { SyncCodec.decodeSchedule(bytes) }.getOrNull() ?: return
        val current = WatchScheduleStore.load(this)
        if (!SyncCodec.shouldApply(incoming, current)) return
        val merged = ScheduleMerge.merge(incoming, current)
        WatchScheduleStore.save(this, merged)
        WatchAlarmScheduler.apply(this, merged)
        // An occurrence the phone closed while this watch was ringing stops here too.
        merged.entries.filter { it.instance.state.isTerminal }.forEach {
            WakeStageService.remoteCommands.tryEmit(Command(it.instance.id, System.currentTimeMillis(), Device.PHONE))
        }
        WatchSurfaces.refresh(this)
        Log.i(TAG, "Applied schedule v${incoming.version} (${incoming.entries.size} entries)")
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            DataLayerPaths.CMD_DISMISS -> runCatching { SyncCodec.decodeCommand(event.data) }.getOrNull()?.let(::remoteClose)
            DataLayerPaths.HEALTH -> {
                rearm()
                val app = application as WearApplication
                app.appScope.launch {
                    val reply = HealthReply(worn = WornSensor.isWorn(this@WatchListenerService), sentAtEpochMs = System.currentTimeMillis())
                    runCatching {
                        Wearable.getMessageClient(this@WatchListenerService)
                            .sendMessage(event.sourceNodeId, DataLayerPaths.HEALTH_REPLY, SyncCodec.encodeAny(reply)).await()
                    }
                    WatchSync.flush(this@WatchListenerService)
                }
            }
        }
    }

    /**
     * The 2R's system force-stops idle third-party apps, which erases their alarms (verified on the watch,
     * 2026-09-28). Every time Play services wakes GOOD (the phone's T−10 ping, a sync, a reconnect), re-register
     * the stored schedule, so a kill between syncs is repaired before the first stage at T−3.
     */
    private fun rearm() {
        WatchScheduleStore.load(this)?.let { WatchAlarmScheduler.apply(this, it) }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        rearm()
        if (info.nodes.any { it.isNearby }) {
            (application as WearApplication).appScope.launch { WatchSync.flush(this@WatchListenerService) }
        }
    }

    /**
     * Closes the occurrence in the state the command carries: DISMISSED when someone stopped it, CANCELLED when the
     * phone disarmed or moved its channel (so it never reads as a wake-up). Idempotent: one already over changes nothing.
     */
    private fun remoteClose(cmd: Command) {
        WakeStageService.remoteCommands.tryEmit(cmd)
        val entry = WatchScheduleStore.find(this, cmd.instanceId) ?: return
        if (entry.instance.state.isTerminal) return
        val closed = if (cmd.state == InstanceState.DISMISSED) {
            entry.instance.copy(
                state = InstanceState.DISMISSED, currentStage = null, dismissedAtStage = entry.instance.currentStage,
                dismissedAtEpochMs = cmd.sentAtEpochMs, dismissedOn = cmd.from,
            )
        } else {
            entry.instance.copy(state = cmd.state, currentStage = null)
        }
        WatchScheduleStore.update(this, closed)
        WatchAlarmScheduler.cancelChannel(this, entry.instance.alarmId)
        WatchSurfaces.refresh(this)
    }

    private companion object {
        const val TAG = "GoodWatchListener"
    }
}
