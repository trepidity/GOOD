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

    private val weekdays = daily.copy(repeatDays = NextOccurrence.WEEKDAYS)

    /** Gate: skip spec — a skipped occurrence never rings, and the next repeat day takes over. 2026-10-01 is a Thursday. */
    @Test
    fun `a pending occurrence on the skip date is closed as skipped and the next repeat day becomes active`() {
        val d = decide(weekdays.copy(skipNextDate = "2026-10-01"), instance(InstanceState.SCHEDULED), now = at("2026-09-30T22:00"))
        assertEquals(InstanceState.SKIPPED, d.skipped!!.state)
        assertEquals(at("2026-10-02T06:30").toEpochMilli(), d.active!!.scheduledAtEpochMs)
        assertNull(d.cancelled)
    }

    /** Gate: skip spec — undo. After a Thursday skip, Friday is pending; clearing the skip brings Thursday back. */
    @Test
    fun `clearing a skip reopens the skipped occurrence and cancels the one after it`() {
        val friday = instance(InstanceState.SCHEDULED, at("2026-10-02T06:30"))
        val d = decide(weekdays, friday, now = at("2026-09-30T22:05"))
        assertEquals(InstanceIds.of(1, t), d.active!!.id)
        assertEquals(InstanceState.SCHEDULED, d.active!!.state)
        assertEquals(InstanceState.CANCELLED, d.cancelled!!.state)
        assertEquals(friday.id, d.cancelled!!.id)
    }

    /** Gate: skip spec — SKIP never cuts off a wake-up that is already running. */
    @Test
    fun `a ringing occurrence on the skip date keeps ringing`() {
        val firing = instance(InstanceState.FIRING)
        assertSame(firing, decide(daily.copy(skipNextDate = "2026-10-01"), firing, now = at("2026-10-01T06:25")).active)
    }

    /** Gate: #6 — disarming during the sunrise stops it. */
    @Test
    fun `disarming a ringing channel cancels its occurrence`() {
        val d = decide(daily.copy(enabled = false), instance(InstanceState.FIRING), now = at("2026-10-01T06:25"))
        assertEquals(InstanceState.CANCELLED, d.cancelled!!.state)
        assertNull(d.active)
    }

    /** Gate: #5 — a replaced occurrence is closed, not left open. */
    @Test
    fun `moving the time cancels the replaced occurrence`() {
        val d = decide(daily.copy(hour = 7, minute = 0), instance(InstanceState.SCHEDULED), now = at("2026-09-30T22:00"))
        assertEquals(InstanceState.CANCELLED, d.cancelled!!.state)
    }

    /** Gate: #5 — an edit that keeps the time (profile, tone, target) must not cancel anything. */
    @Test
    fun `changing only the profile keeps the pending occurrence`() {
        val pending = instance(InstanceState.SCHEDULED)
        val d = decide(daily.copy(profileId = WakeProfile.QUICK.id), pending, now = at("2026-09-30T22:00"))
        assertSame(pending, d.active)
        assertNull(d.cancelled)
    }

    /** Gate: current-occurrence rule — moving an alarm earlier the same day still rings today. */
    @Test
    fun `after moving 7 00 to 6 30 the next run keeps 6 30 active`() {
        val seven = instance(InstanceState.SCHEDULED, at("2026-10-01T07:00"))
        val first = decide(daily, seven, now = at("2026-09-30T22:00"))
        val rows = listOf(first.cancelled!!, first.active!!)
        val second = decide(daily, ScheduleBuilder.current(rows), now = at("2026-09-30T22:01"))
        assertEquals(t.toEpochMilli(), second.active!!.scheduledAtEpochMs)
        assertNull(second.cancelled)
    }
}
