package com.trepidity.good.sleep

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Where a session came from. Declaration order is priority, highest first. */
enum class SleepSource { HEALTH_CONNECT, WATCH, PHONE, ANCHORS }

enum class SegmentKind { ASLEEP, AWAKE, LIGHT, DEEP, REM }

data class Segment(val start: Instant, val end: Instant, val kind: SegmentKind) {
    val duration: Duration get() = Duration.between(start, end)
}

/** A session read from Health Connect (written by any app, including GOOD itself). */
data class ExternalSession(
    val id: String,
    val start: Instant,
    val end: Instant,
    val sourcePackage: String,
    val stages: List<Segment>,
)

/**
 * A point asleep/awake sample from the phone (Sleep API) or watch (Health Services passive state).
 * The state holds until the next sample from the same source.
 */
data class Sample(val at: Instant, val asleep: Boolean, val source: SleepSource)

/** Bed-button press and alarm dismiss time for one night; either may be missing. */
data class Anchors(val bedtime: Instant?, val dismissedAt: Instant?)

/**
 * A session built for one night, not yet stored. For sample-derived sessions [start] is sleep onset
 * and [end] is wake; for anchor-only sessions they are bed-button and dismiss.
 */
data class SessionDraft(
    val start: Instant,
    val end: Instant,
    val source: SleepSource,
    val sourcePackage: String?,
    val externalId: String?,
    val segments: List<Segment>,
    val awakenings: Int,
) {
    val timeInBedMin: Int get() = Duration.between(start, end).toMinutes().toInt()

    /** Sum of sleep segments when any exist; otherwise the whole session minus AWAKE segments. */
    val totalSleepMin: Int
        get() {
            val sleep = segments.filter { it.kind != SegmentKind.AWAKE }
            return if (sleep.isNotEmpty()) {
                sleep.sumOf { it.duration.toMinutes() }.toInt()
            } else {
                timeInBedMin - segments.sumOf { it.duration.toMinutes() }.toInt()
            }
        }
}

/** One stored night, as the metrics need it. */
data class Night(val wakeDate: LocalDate, val totalSleepMin: Int, val bedtime: Instant)
