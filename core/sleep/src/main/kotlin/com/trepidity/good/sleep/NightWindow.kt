package com.trepidity.good.sleep

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The span of one night: 18:00 local on the day before [wakeDate] until 14:00 local on [wakeDate]. */
data class NightWindow(val wakeDate: LocalDate, val start: Instant, val end: Instant) {

    /** Start inclusive, end exclusive. */
    operator fun contains(at: Instant): Boolean = !at.isBefore(start) && at.isBefore(end)

    companion object {
        private val EVENING: LocalTime = LocalTime.of(18, 0)
        private val AFTERNOON: LocalTime = LocalTime.of(14, 0)

        /** Built from local wall-clock times, so DST nights are 19 or 21 hours long. */
        fun forWakeDate(wakeDate: LocalDate, zone: ZoneId): NightWindow = NightWindow(
            wakeDate,
            ZonedDateTime.of(wakeDate.minusDays(1), EVENING, zone).toInstant(),
            ZonedDateTime.of(wakeDate, AFTERNOON, zone).toInstant(),
        )

        /** The wake date of the night [at] falls in, or null for the 14:00–18:00 gap between nights. */
        fun wakeDateOf(at: Instant, zone: ZoneId): LocalDate? {
            val local = at.atZone(zone)
            val time = local.toLocalTime()
            return when {
                time < AFTERNOON -> local.toLocalDate()
                time >= EVENING -> local.toLocalDate().plusDays(1)
                else -> null
            }
        }
    }
}
