package com.trepidity.good.wake

import com.trepidity.good.model.ScheduleEntry
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * I'M UP closes every open occurrence due today before 14:00, ringing or not. The cutoff matches the night
 * window's end, so a press in the evening never cancels tomorrow's alarm. Phone and watch both call this, so an
 * offline watch and the phone agree on what was closed.
 */
object ImUp {
    private val CUTOFF: LocalTime = LocalTime.of(14, 0)

    fun targets(entries: List<ScheduleEntry>, now: Instant, zone: ZoneId): List<ScheduleEntry> {
        val today = now.atZone(zone).toLocalDate()
        return entries.filter { e ->
            val t = Instant.ofEpochMilli(e.instance.scheduledAtEpochMs).atZone(zone)
            e.instance.alarmId > 0 && !e.instance.state.isTerminal && t.toLocalDate() == today && t.toLocalTime() < CUTOFF
        }
    }
}
