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
 * I'M UP on the phone: close every alarm still due this morning as dismissed before ringing, through the same
 * path as a dismiss, so the watch stops too; then stamp the wake time once (I'M UP spec). Closing comes first so
 * a slow or failing sleep write (Health Connect) never delays silencing a ringing alarm.
 */
object WakeUp {
    suspend fun record(context: Context, at: Instant): Int {
        val entries = ScheduleStore.load(context)?.entries.orEmpty()
        val targets = ImUp.targets(entries, at, ZoneId.systemDefault())
        for (e in targets) {
            val closed = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.PHONE), at, e.profile)
            val cmd = Command(closed.id, at.toEpochMilli(), Device.PHONE, stage = e.instance.currentStage)
            WakeService.remoteCommands.tryEmit(cmd)
            InstanceEvents.record(context, closed, stampWake = false)
            val sent = runCatching { PhoneSync.sendDismiss(context, closed, cmd) }
            EventLog.log(
                context, "DISMISS",
                "${closed.id} by I'M UP · " + sent.fold({ "watch nodes reached: $it" }, { "send failed: ${it.message}" }),
            )
        }
        AppGraph.sleep(context).onWake(at)
        EventLog.log(context, "IM_UP", "${targets.size} alarm(s) closed")
        AppGraph.alarms(context).rescheduleAll("i'm up")
        return targets.size
    }
}
