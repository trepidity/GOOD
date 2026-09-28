package com.trepidity.good.wake

import com.trepidity.good.model.Alarm
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object NextOccurrence {

    /**
     * Next time [alarm] should ring after [now], in [zone]. Returns null for a disabled alarm.
     * A wall-clock time that falls in a DST gap is moved forward by the gap (java.time's rule),
     * so a 02:30 alarm on spring-forward night rings at 03:30.
     */
    fun nextFireTime(alarm: Alarm, now: Instant, zone: ZoneId): Instant? {
        if (!alarm.enabled) return null
        val nowZ = now.atZone(zone)
        val time = LocalTime.of(alarm.hour, alarm.minute)
        for (dayOffset in 0L..14L) {
            val date = nowZ.toLocalDate().plusDays(dayOffset)
            val candidate = ZonedDateTime.of(date, time, zone)
            if (!candidate.isAfter(nowZ)) continue
            if (alarm.repeatDays != 0 && !repeatsOn(alarm.repeatDays, date.dayOfWeek)) continue
            if (alarm.skipNextDate == date.toString()) continue
            return candidate.toInstant()
        }
        return null
    }

    fun repeatsOn(mask: Int, day: DayOfWeek): Boolean = mask and (1 shl (day.value - 1)) != 0

    fun mask(vararg days: DayOfWeek): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }

    val WEEKDAYS: Int = mask(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
}
