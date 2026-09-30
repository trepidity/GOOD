package com.trepidity.good.sleep

import java.time.Duration
import java.time.Instant

/**
 * A skipped night has no dismiss to end it. Its wake is the first sign of being up (a phone unlock or a watch
 * "awake" state) at or after the skipped alarm time, inside the night window and under 24 h after the night began
 * (#1). Candidates before the alarm time are ignored: a 3 a.m. unlock is not getting up.
 */
object WakeInference {
    private val MAX_NIGHT: Duration = Duration.ofHours(24)

    fun infer(skippedAt: Instant, candidates: List<Instant>, nightStart: Instant, window: NightWindow): Instant? =
        candidates
            .filter { !it.isBefore(skippedAt) && it in window && Duration.between(nightStart, it) < MAX_NIGHT }
            .minOrNull()
}
