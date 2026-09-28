package com.trepidity.good.sleep

import java.time.Instant

object BedtimeReminder {
    /** When to remind: [nextAlarm] minus the sleep goal minus a wind-down. */
    fun at(nextAlarm: Instant, goalMin: Int, windDownMin: Int = 15): Instant =
        nextAlarm.minusSeconds((goalMin + windDownMin) * 60L)
}
