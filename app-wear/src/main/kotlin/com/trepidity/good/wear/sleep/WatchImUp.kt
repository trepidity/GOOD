package com.trepidity.good.wear.sleep

import android.content.Context
import com.trepidity.good.model.Command
import com.trepidity.good.model.Device
import com.trepidity.good.model.WakeAnchorMessage
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.wake.ImUp
import com.trepidity.good.wake.WakeEvent
import com.trepidity.good.wake.WakeStateMachine
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.surface.WatchSurfaces
import com.trepidity.good.wear.sync.WatchSync
import com.trepidity.good.wear.wake.WakeStageService
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * I'M UP on the watch: close this morning's alarms here at once (so it works with the phone out of range), then
 * tell the phone through the outbox: one dismiss per alarm and the wake time (I'M UP spec).
 */
object WatchImUp {
    fun record(context: Context, at: Instant): Int {
        val entries = WatchScheduleStore.load(context)?.entries.orEmpty()
        val targets = ImUp.targets(entries, at, ZoneId.systemDefault())
        val app = context.applicationContext as WearApplication
        for (e in targets) {
            val closed = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.WATCH), at, e.profile)
            val cmd = Command(closed.id, at.toEpochMilli(), Device.WATCH, stage = e.instance.currentStage)
            WatchScheduleStore.update(context, closed)
            WatchAlarmScheduler.cancelChannel(context, closed.alarmId)
            WakeStageService.remoteCommands.tryEmit(cmd)
            app.appScope.launch { runCatching { WatchSync.sendDismiss(context, closed, cmd) } }
        }
        WatchScheduleStore.recordLocalWake(context, at.toEpochMilli())
        app.appScope.launch { WatchSync.send(context, DataLayerPaths.SLEEP_WAKE, SyncCodec.encodeAny(WakeAnchorMessage(at.toEpochMilli()))) }
        WatchSurfaces.refresh(context)
        return targets.size
    }
}
