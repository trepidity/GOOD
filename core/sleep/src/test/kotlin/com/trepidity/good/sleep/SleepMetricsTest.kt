package com.trepidity.good.sleep

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Gate: SPEC Sleep tracking "Metrics shown" — averages, sleep debt, bedtime consistency. */
class SleepMetricsTest {
    private val today = LocalDate.parse("2026-10-10")
    private fun night(daysAgo: Long, min: Int) =
        Night(today.minusDays(daysAgo), min, at("2026-10-01T23:00"))

    @Test
    fun `sleep debt ignores nights over goal instead of letting them pay it down`() {
        val nights = listOf(night(0, 400), night(1, 540), night(2, 450))
        assertEquals(50, SleepMetrics.sleepDebtMin(nights, goalMin = 450, today = today))
    }

    @Test
    fun `a 7-day average includes today and excludes the night 7 days back`() {
        val nights = listOf(night(0, 400), night(6, 500), night(7, 100))
        assertEquals(450, SleepMetrics.averageSleepMin(nights, days = 7, today = today))
    }

    @Test
    fun `bedtimes either side of midnight are minutes apart, not hours`() {
        val spread = SleepMetrics.bedtimeSpreadMin(listOf(at("2026-10-01T23:50"), at("2026-10-03T00:10")), CHICAGO)
        assertEquals(10, spread)
    }
}
