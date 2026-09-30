package com.trepidity.good.phone.alarm

import android.content.Context
import com.trepidity.good.model.Command
import com.trepidity.good.model.Device
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.phone.wake.WakeService
import com.trepidity.good.wake.ImUp
import com.trepidity.good.wake.WakeEvent
import com.trepidity.good.wake.WakeStateMachine
import java.time.Instant
import java.time.ZoneId

/**
 * I'M UP on the phone: stamp the wake time once, then close every alarm still due this morning as dismissed
 * before ringing, through the same path as a dismiss, so the watch stops too (I'M UP spec).
 */
object WakeUp {
    suspend fun record(context: Context, at: Instant): Int {
        AppGraph.sleep(context).onWake(at)
        val entries = ScheduleStore.load(context)?.entries.orEmpty()
        val targets = ImUp.targets(entries, at, ZoneId.systemDefault())
        for (e in targets) {
            val closed = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.PHONE), at, e.profile)
            val cmd = Command(closed.id, at.toEpochMilli(), Device.PHONE)
            WakeService.remoteCommands.tryEmit(cmd)
            InstanceEvents.record(context, closed, stampWake = false)
            runCatching { PhoneSync.sendDismiss(context, closed, cmd) }
        }
        EventLog.log(context, "IM_UP", "${targets.size} alarm(s) closed")
        AppGraph.alarms(context).rescheduleAll("i'm up")
        return targets.size
    }
}
