package com.trepidity.good.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

/** Gate: SPEC Sleep tracking step 1 — each night runs 18:00 to 14:00 local and belongs to the wake date. */
class NightWindowTest {

    @Test
    fun `the window runs from 18_00 the day before to 14_00 on the wake date`() {
        val w = NightWindow.forWakeDate(LocalDate.parse("2026-10-02"), CHICAGO)
        assertEquals(at("2026-10-01T18:00"), w.start)
        assertEquals(at("2026-10-02T14:00"), w.end)
    }

    @Test
    fun `spring-forward night is an hour shorter so 14_00 stays 14_00 local`() {
        val w = NightWindow.forWakeDate(LocalDate.parse("2026-03-08"), CHICAGO)
        assertEquals(Duration.ofHours(19), Duration.between(w.start, w.end))
        assertEquals(at("2026-03-08T14:00"), w.end)
    }

    @Test
    fun `13_59 belongs to today's night, 14_00 to no night, 18_00 to tomorrow's`() {
        assertEquals(LocalDate.parse("2026-10-02"), NightWindow.wakeDateOf(at("2026-10-02T13:59"), CHICAGO))
        assertNull(NightWindow.wakeDateOf(at("2026-10-02T14:00"), CHICAGO))
        assertNull(NightWindow.wakeDateOf(at("2026-10-02T17:59"), CHICAGO))
        assertEquals(LocalDate.parse("2026-10-03"), NightWindow.wakeDateOf(at("2026-10-02T18:00"), CHICAGO))
    }
}
