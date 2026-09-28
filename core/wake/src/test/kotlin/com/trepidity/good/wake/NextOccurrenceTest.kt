package com.trepidity.good.wake

import com.trepidity.good.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class NextOccurrenceTest {
    private val chicago = ZoneId.of("America/Chicago")
    private fun at(s: String) = ZonedDateTime.parse(s).toInstant()

    @Test
    fun `one-shot alarm rings tomorrow when today's time has passed`() {
        val alarm = Alarm(id = 1, hour = 6, minute = 30)
        val now = at("2026-09-27T22:00-05:00[America/Chicago]")
        assertEquals(at("2026-09-28T06:30-05:00[America/Chicago]"), NextOccurrence.nextFireTime(alarm, now, chicago))
    }

    @Test
    fun `weekday alarm skips the weekend`() {
        val alarm = Alarm(id = 1, hour = 6, minute = 30, repeatDays = NextOccurrence.WEEKDAYS)
        val fridayNight = at("2026-10-02T22:00-05:00[America/Chicago]")
        assertEquals(at("2026-10-05T06:30-05:00[America/Chicago]"), NextOccurrence.nextFireTime(alarm, fridayNight, chicago))
    }

    @Test
    fun `skip next date moves to the following repeat day`() {
        val alarm = Alarm(id = 1, hour = 6, minute = 30, repeatDays = NextOccurrence.WEEKDAYS, skipNextDate = "2026-09-28")
        val sundayNight = at("2026-09-27T22:00-05:00[America/Chicago]")
        assertEquals(at("2026-09-29T06:30-05:00[America/Chicago]"), NextOccurrence.nextFireTime(alarm, sundayNight, chicago))
    }

    @Test
    fun `alarm inside the spring-forward gap rings an hour later`() {
        val alarm = Alarm(id = 1, hour = 2, minute = 30)
        val now = at("2027-03-13T23:00-06:00[America/Chicago]")
        assertEquals(at("2027-03-14T03:30-05:00[America/Chicago]"), NextOccurrence.nextFireTime(alarm, now, chicago))
    }

    @Test
    fun `disabled alarm has no next time`() {
        assertNull(NextOccurrence.nextFireTime(Alarm(id = 1, hour = 6, minute = 0, enabled = false), Instant.EPOCH, chicago))
    }
}
