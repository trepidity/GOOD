package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.StageType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gate: #7 — the wake-behaviour metric, with I'M UP's early dismisses shown apart. */
class WakeBehaviourTest {
    private val t = 1_790_000_000_000L
    private val base = AlarmInstance("a1", 1, t)

    @Test
    fun `a dismiss during the sound stage reports the stage and minutes after the first stage`() {
        val i = base.copy(
            state = InstanceState.DISMISSED, firstStageAtEpochMs = t - 600_000, dismissedAtStage = StageType.SOUND,
            dismissedAtEpochMs = t - 600_000 + 6 * 60_000 + 59_000, dismissedOn = Device.PHONE,
        )
        assertEquals(WakeBehaviour.Dismissed(StageType.SOUND, 6), WakeBehaviour.of(i))
    }

    @Test
    fun `a dismiss before any stage is up early`() {
        val i = base.copy(state = InstanceState.DISMISSED, dismissedAtEpochMs = t - 3_600_000, dismissedOn = Device.WATCH)
        assertEquals(WakeBehaviour.UpEarly, WakeBehaviour.of(i))
    }
}
