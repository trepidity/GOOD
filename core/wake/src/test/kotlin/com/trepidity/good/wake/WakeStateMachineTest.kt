package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.StageType
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class WakeStateMachineTest {
    private val now = Instant.parse("2026-10-01T11:30:00Z")
    private val profile = WakeProfile.GENTLE
    private val scheduled = AlarmInstance("i1", 1, now.toEpochMilli())

    @Test
    fun `stage start moves the instance to firing`() {
        val next = WakeStateMachine.reduce(scheduled, WakeEvent.StageStarted(StageType.LIGHT), now, profile)
        assertEquals(InstanceState.FIRING, next.state)
        assertEquals(StageType.LIGHT, next.currentStage)
    }

    @Test
    fun `dismiss records when and where`() {
        val firing = scheduled.copy(state = InstanceState.FIRING, currentStage = StageType.SOUND)
        val next = WakeStateMachine.reduce(firing, WakeEvent.Dismiss(Device.WATCH), now, profile)
        assertEquals(InstanceState.DISMISSED, next.state)
        assertEquals(Device.WATCH, next.dismissedOn)
        assertEquals(now.toEpochMilli(), next.dismissedAtEpochMs)
        assertNull(next.currentStage)
    }

    @Test
    fun `terminal states ignore later events`() {
        val done = WakeStateMachine.reduce(scheduled, WakeEvent.AutoSilence, now, profile)
        val after = WakeStateMachine.reduce(done, WakeEvent.StageStarted(StageType.ESCALATE), now, profile)
        assertEquals(InstanceState.SILENCED, after.state)
        assertEquals(done, after)
    }
}
