package com.trepidity.good.sleep

import java.time.Instant
import java.time.ZoneId

enum class SleepAction { BED, UP }

/**
 * The bed button is a start/stop toggle: after tonight's GOOD NIGHT it becomes GOOD MORNING (I'M UP). Only a bed
 * press inside the current night window counts, so a press from a previous night never leaves it stuck on UP.
 */
object SleepToggle {
    fun next(lastBed: Instant?, lastWake: Instant?, now: Instant, zone: ZoneId): SleepAction {
        val date = NightWindow.wakeDateOf(now, zone) ?: return SleepAction.BED
        val bed = lastBed?.takeIf { it in NightWindow.forWakeDate(date, zone) && !it.isAfter(now) } ?: return SleepAction.BED
        return if (lastWake != null && !lastWake.isBefore(bed)) SleepAction.BED else SleepAction.UP
    }
}
