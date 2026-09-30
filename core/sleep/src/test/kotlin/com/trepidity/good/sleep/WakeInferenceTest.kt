package com.trepidity.good.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Gate: #1 — a skipped night's wake is the first unlock or watch "awake" after the skipped alarm, within 24 h. */
class WakeInferenceTest {
    private val window = NightWindow.forWakeDate(LocalDate.parse("2026-10-01"), CHICAGO)
    private val skipped = at("2026-10-01T06:30")
    private val bed = at("2026-09-30T23:00")

    @Test
    fun `candidates before the skipped alarm time are ignored and the earliest after it wins`() {
        val c = listOf(at("2026-10-01T03:10"), at("2026-10-01T09:40"), at("2026-10-01T09:10"))
        assertEquals(at("2026-10-01T09:10"), WakeInference.infer(skipped, c, bed, window))
    }

    @Test
    fun `a candidate outside the night window is not a wake`() {
        assertNull(WakeInference.infer(skipped, listOf(at("2026-10-01T15:00")), bed, window))
    }

    @Test
    fun `a candidate 24 h or more after the night start is rejected`() {
        val longWindow = NightWindow(window.wakeDate, window.start, at("2026-10-02T12:00"))
        assertNull(WakeInference.infer(skipped, listOf(at("2026-10-01T23:00")), bed, longWindow))
    }
}
