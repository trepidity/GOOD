package com.trepidity.good.sleep

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object SleepMetrics {
    private const val MINUTES_PER_DAY = 24 * 60

    /** Mean total sleep over nights whose wake date is in (today - days, today]; null if none. */
    fun averageSleepMin(nights: List<Night>, days: Int, today: LocalDate): Int? {
        val inRange = nights.inLast(days, today)
        if (inRange.isEmpty()) return null
        return inRange.map { it.totalSleepMin }.average().roundToInt()
    }

    /** Sum of shortfalls against [goalMin] over recorded nights in range; nights over goal add nothing. */
    fun sleepDebtMin(nights: List<Night>, goalMin: Int, today: LocalDate, days: Int = 7): Int =
        nights.inLast(days, today).sumOf { maxOf(0, goalMin - it.totalSleepMin) }

    /**
     * Spread of [bedtimes] (callers pass the last 14 nights) as a circular standard deviation in
     * minutes, so 23:50 and 00:10 read as close together rather than 23 h apart. Null if empty.
     */
    fun bedtimeSpreadMin(bedtimes: List<Instant>, zone: ZoneId): Int? {
        if (bedtimes.isEmpty()) return null
        val angles = bedtimes.map {
            val t = it.atZone(zone).toLocalTime()
            (t.hour * 60 + t.minute + t.second / 60.0) / MINUTES_PER_DAY * 2 * PI
        }
        val r = hypot(angles.map { cos(it) }.average(), angles.map { sin(it) }.average()).coerceIn(1e-12, 1.0)
        val sdRadians = sqrt(-2 * ln(r))
        return (sdRadians / (2 * PI) * MINUTES_PER_DAY).roundToInt()
    }

    /** Minutes from bed button to sleep onset; null without a bed-button press, never negative. */
    fun onsetLatencyMin(bedtime: Instant?, sleepStart: Instant): Int? =
        bedtime?.let { Duration.between(it, sleepStart).toMinutes().toInt().coerceAtLeast(0) }

    private fun List<Night>.inLast(days: Int, today: LocalDate): List<Night> {
        val after = today.minusDays(days.toLong())
        return filter { it.wakeDate.isAfter(after) && !it.wakeDate.isAfter(today) }
    }
}
