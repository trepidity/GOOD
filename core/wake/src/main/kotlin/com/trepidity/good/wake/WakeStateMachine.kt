package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.StageType
import com.trepidity.good.model.WakeProfile
import java.time.Instant

sealed interface WakeEvent {
    data class StageStarted(val type: StageType) : WakeEvent
    data class Dismiss(val by: Device) : WakeEvent
    data object AutoSilence : WakeEvent
}

/** Pure reducer for an alarm occurrence. There is no snooze: the only ways out are dismiss or auto-silence. */
object WakeStateMachine {

    fun reduce(instance: AlarmInstance, event: WakeEvent, now: Instant, profile: WakeProfile): AlarmInstance {
        if (instance.state.isTerminal) return instance
        return when (event) {
            is WakeEvent.StageStarted -> instance.copy(
                state = InstanceState.FIRING,
                currentStage = event.type,
                firstStageAtEpochMs = instance.firstStageAtEpochMs ?: now.toEpochMilli(),
            )

            is WakeEvent.Dismiss -> dismissed(instance, now, event.by)

            WakeEvent.AutoSilence -> instance.copy(state = InstanceState.SILENCED, currentStage = null)
        }
    }

    private fun dismissed(instance: AlarmInstance, now: Instant, by: Device) = instance.copy(
        state = InstanceState.DISMISSED,
        dismissedAtStage = instance.currentStage,
        currentStage = null,
        dismissedAtEpochMs = now.toEpochMilli(),
        dismissedOn = by,
    )
}
