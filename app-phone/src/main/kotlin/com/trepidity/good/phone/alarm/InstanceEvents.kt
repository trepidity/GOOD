package com.trepidity.good.phone.alarm

import android.content.Context
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.GoodApplication
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The one place an occurrence's state changes on the phone. The device-protected snapshot is updated at once
 * (works before first unlock); Room, the next occurrence and the sleep log follow when the phone is unlocked,
 * or at BOOT_COMPLETED via [AlarmRepository.rescheduleAll]'s reconcile step.
 */
object InstanceEvents {

    fun record(context: Context, instance: AlarmInstance) {
        ScheduleStore.update(context, instance)
        if (!AppGraph.isUnlocked(context)) return
        val app = context.applicationContext as GoodApplication
        app.appScope.launch {
            val repo = AppGraph.alarms(app)
            if (instance.alarmId != 0L) repo.markInstance(instance)
            if (instance.state.isTerminal) {
                if (instance.state == InstanceState.DISMISSED) {
                    AppGraph.sleep(app).onWake(Instant.ofEpochMilli(instance.dismissedAtEpochMs ?: System.currentTimeMillis()))
                }
                repo.rescheduleAll("instance ${instance.state}")
            }
        }
    }
}
