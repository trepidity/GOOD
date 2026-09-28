package com.trepidity.good.wake

import com.trepidity.good.model.Alarm
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Gate: SPEC Reliability rule 7 / REVIEW R5 — the schedule rolls forward exactly once per occurrence. */
class ScheduleBuilderTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(s: String): Instant = ZonedDateTime.parse("$s-05:00[America/Chicago]").toInstant()
    private val daily = Alarm(id = 1, hour = 6, minute = 30, repeatDays = 0b1111111)
    private val t = at("2026-10-01T06:30")

    private fun instance(state: InstanceState, fireAt: Instant = t) =
        InstanceIds.of(1, fireAt).let { com.trepidity.good.model.AlarmInstance(it, 1, fireAt.toEpochMilli(), state) }

    private fun decide(alarm: Alarm, last: com.trepidity.good.model.AlarmInstance?, now: Instant) =
        ScheduleBuilder.decide(Channel(alarm, WakeProfile.GENTLE, last), now, zone)

    @Test
    fun `dismissing during the light stage does not re-arm the same morning`() {
        val d = decide(daily, instance(InstanceState.DISMISSED), now = at("2026-10-01T06:25"))
        assertEquals(at("2026-10-02T06:30").toEpochMilli(), d.active!!.scheduledAtEpochMs)
    }

    @Test
    fun `a ringing instance is kept until its auto-silence time`() {
        val firing = instance(InstanceState.FIRING)
        assertSame(firing, decide(daily, firing, now = at("2026-10-01T06:45")).active)
    }

    @Test
    fun `an occurrence that never rang before its silence time is reported missed and the next one armed`() {
        val d = decide(daily, instance(InstanceState.SCHEDULED), now = at("2026-10-01T07:00"))
        assertEquals(InstanceState.SILENCED, d.missed!!.state)
        assertEquals(at("2026-10-02T06:30").toEpochMilli(), d.active!!.scheduledAtEpochMs)
    }

    @Test
    fun `editing the time replaces the pending occurrence`() {
        val d = decide(daily.copy(hour = 7, minute = 0), instance(InstanceState.SCHEDULED), now = at("2026-09-30T22:00"))
        assertEquals(at("2026-10-01T07:00").toEpochMilli(), d.active!!.scheduledAtEpochMs)
        assertNull(d.missed)
    }

    @Test
    fun `an unchanged pending occurrence keeps its identity and state`() {
        val pending = instance(InstanceState.SCHEDULED)
        assertSame(pending, decide(daily, pending, now = at("2026-10-01T06:22")).active)
    }

    @Test
    fun `a disarmed channel registers nothing`() {
        assertNull(decide(daily.copy(enabled = false), instance(InstanceState.SCHEDULED), at("2026-09-30T22:00")).active)
    }

    /** Review follow-up: the alarm broadcast never arrived (dropped, or a reboot); opening the app must not skip it. */
    @Test
    fun `an overdue occurrence that never rang is kept so it rings late instead of rolling to tomorrow`() {
        val overdue = instance(InstanceState.SCHEDULED)
        assertSame(overdue, decide(daily, overdue, now = at("2026-10-01T06:34")).active)
    }

    @Test
    fun `an overdue occurrence of a channel that was edited since is replaced, not rung`() {
        val d = decide(daily.copy(hour = 7, minute = 0), instance(InstanceState.SCHEDULED), now = at("2026-10-01T06:34"))
        assertEquals(at("2026-10-01T07:00").toEpochMilli(), d.active!!.scheduledAtEpochMs)
    }
}
