package com.trepidity.good.wake

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Gate: I'M UP spec — targets are today's open occurrences before 14:00, never tomorrow's, never a CHK test. */
class ImUpTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(s: String): Instant = ZonedDateTime.parse("$s-05:00[America/Chicago]").toInstant()
    private fun entry(fireAt: String, channel: Long = 1, state: InstanceState = InstanceState.SCHEDULED) = ScheduleEntry(
        AlarmInstance(InstanceIds.of(channel, at(fireAt)), channel, at(fireAt).toEpochMilli(), state), WakeProfile.GENTLE, SoundTarget.AUTO,
    )

    @Test
    fun `a 05 00 press targets this morning's 06 30`() {
        val today = entry("2026-10-01T06:30")
        assertEquals(listOf(today), ImUp.targets(listOf(today), at("2026-10-01T05:00"), zone))
    }

    @Test
    fun `a 22 00 press never targets tomorrow morning`() {
        assertEquals(emptyList<ScheduleEntry>(), ImUp.targets(listOf(entry("2026-10-01T06:30")), at("2026-09-30T22:00"), zone))
    }

    @Test
    fun `13 59 is a target and 14 00 is not`() {
        val before = entry("2026-10-01T13:59", channel = 1)
        val atCutoff = entry("2026-10-01T14:00", channel = 2)
        assertEquals(listOf(before), ImUp.targets(listOf(before, atCutoff), at("2026-10-01T09:00"), zone))
    }

    @Test
    fun `a ringing occurrence is a target but a closed one and a CHK test alarm are not`() {
        val ringing = entry("2026-10-01T06:30", channel = 1, state = InstanceState.FIRING)
        val closed = entry("2026-10-01T07:00", channel = 2, state = InstanceState.SKIPPED)
        val test = entry("2026-10-01T06:40", channel = 0)
        assertEquals(listOf(ringing), ImUp.targets(listOf(ringing, closed, test), at("2026-10-01T06:25"), zone))
    }
}
