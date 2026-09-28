package com.trepidity.good.sleep

import java.time.Duration
import java.time.Instant

object SessionBuilder {
    private val ONSET_RUN: Duration = Duration.ofMinutes(20)
    private val AWAKENING: Duration = Duration.ofMinutes(5)

    /**
     * Best session for one night, or null if there is not enough data. Priority: a Health Connect
     * session from another app, then watch samples, then phone samples, then bed + dismiss anchors.
     * Sessions from [ownPackage] (GOOD's own write-back) are never treated as external.
     */
    fun build(
        window: NightWindow,
        external: List<ExternalSession>,
        samples: List<Sample>,
        anchors: Anchors,
        ownPackage: String,
    ): SessionDraft? =
        fromExternal(window, external, ownPackage)
            ?: fromSamples(window, samples, anchors, SleepSource.WATCH)
            ?: fromSamples(window, samples, anchors, SleepSource.PHONE)
            ?: fromAnchors(anchors)

    /**
     * Whether [incoming] should replace the stored session. Edited sessions are never replaced;
     * a lower-priority source never replaces a higher one; equal or higher priority does.
     */
    fun shouldReplace(existingSource: SleepSource, existingEdited: Boolean, incoming: SessionDraft): Boolean =
        !existingEdited && incoming.source.ordinal <= existingSource.ordinal

    private fun fromExternal(window: NightWindow, external: List<ExternalSession>, ownPackage: String): SessionDraft? {
        val best = external
            .filter { it.sourcePackage != ownPackage }
            .map { it to overlap(it.start, it.end, window) }
            .filter { (_, o) -> o > Duration.ZERO }
            .maxByOrNull { (_, o) -> o }
            ?.first ?: return null
        val stages = best.stages
            .map { Segment(maxOf(it.start, best.start), minOf(it.end, best.end), it.kind) }
            .filter { it.start < it.end }
            .sortedBy { it.start }
        return SessionDraft(
            start = best.start,
            end = best.end,
            source = SleepSource.HEALTH_CONNECT,
            sourcePackage = best.sourcePackage,
            externalId = best.id,
            segments = stages,
            awakenings = stages.count { it.kind == SegmentKind.AWAKE && it.duration >= AWAKENING },
        )
    }

    /**
     * Onset = start of the first asleep run of at least 20 min; wake = end of the last asleep run.
     * Runs end at the next opposite sample, else at dismiss (or window end). Only samples inside the
     * window, at or after the bed button, and before dismiss are considered.
     */
    private fun fromSamples(window: NightWindow, all: List<Sample>, anchors: Anchors, source: SleepSource): SessionDraft? {
        val horizon = anchors.dismissedAt?.let { minOf(it, window.end) } ?: window.end
        val points = all
            .filter { it.source == source && it.at in window && it.at < horizon }
            .filter { anchors.bedtime == null || it.at >= anchors.bedtime }
            .sortedBy { it.at }
        val runs = toRuns(points, horizon)
        val onsetIdx = runs.indexOfFirst { it.kind == SegmentKind.ASLEEP && it.duration >= ONSET_RUN }
        if (onsetIdx < 0) return null
        val wakeIdx = runs.indexOfLast { it.kind == SegmentKind.ASLEEP }
        val night = runs.subList(onsetIdx, wakeIdx + 1)
        return SessionDraft(
            start = night.first().start,
            end = night.last().end,
            source = source,
            sourcePackage = null,
            externalId = null,
            segments = night,
            awakenings = night.count { it.kind == SegmentKind.AWAKE && it.duration >= AWAKENING },
        )
    }

    /** Collapses consecutive same-state samples into ASLEEP/AWAKE runs; the last run ends at [horizon]. */
    private fun toRuns(points: List<Sample>, horizon: Instant): List<Segment> {
        val changes = points.filterIndexed { i, p -> i == 0 || p.asleep != points[i - 1].asleep }
        return changes.mapIndexed { i, p ->
            val end = changes.getOrNull(i + 1)?.at ?: horizon
            Segment(p.at, end, if (p.asleep) SegmentKind.ASLEEP else SegmentKind.AWAKE)
        }
    }

    private fun fromAnchors(anchors: Anchors): SessionDraft? {
        val bed = anchors.bedtime ?: return null
        val dismiss = anchors.dismissedAt ?: return null
        if (!dismiss.isAfter(bed)) return null
        return SessionDraft(bed, dismiss, SleepSource.ANCHORS, null, null, emptyList(), 0)
    }

    private fun overlap(start: Instant, end: Instant, window: NightWindow): Duration {
        val s = maxOf(start, window.start)
        val e = minOf(end, window.end)
        return if (e > s) Duration.between(s, e) else Duration.ZERO
    }
}
