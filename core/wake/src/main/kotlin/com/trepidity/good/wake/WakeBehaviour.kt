package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.StageType

/** How one morning's alarm ended, for the SLP lap (SPEC Sleep tracking → Wake behaviour, #7). */
sealed interface WakeBehaviour {
    /** Dismissed during [stage], [minutesAfterFirstStage] after the first stage started. */
    data class Dismissed(val stage: StageType, val minutesAfterFirstStage: Long) : WakeBehaviour
    /** Closed by I'M UP (or a dismiss) before any stage started. */
    data object UpEarly : WakeBehaviour
    data object Skipped : WakeBehaviour
    data object NoAnswer : WakeBehaviour

    companion object {
        /** Null for an occurrence that is still open or was CANCELLED by an edit. */
        fun of(instance: AlarmInstance): WakeBehaviour? = when (instance.state) {
            InstanceState.DISMISSED -> {
                val first = instance.firstStageAtEpochMs
                val stage = instance.dismissedAtStage
                val at = instance.dismissedAtEpochMs
                if (first == null || stage == null || at == null) UpEarly else Dismissed(stage, (at - first) / 60_000)
            }
            InstanceState.SKIPPED -> Skipped
            InstanceState.SILENCED -> NoAnswer
            InstanceState.SCHEDULED, InstanceState.FIRING, InstanceState.CANCELLED -> null
        }
    }
}
