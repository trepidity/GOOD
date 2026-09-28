package com.trepidity.good.sleep

import com.trepidity.good.sleep.SegmentKind.ASLEEP
import com.trepidity.good.sleep.SegmentKind.AWAKE
import com.trepidity.good.sleep.SegmentKind.DEEP
import com.trepidity.good.sleep.SegmentKind.LIGHT
import com.trepidity.good.sleep.SleepSource.ANCHORS
import com.trepidity.good.sleep.SleepSource.HEALTH_CONNECT
import com.trepidity.good.sleep.SleepSource.PHONE
import com.trepidity.good.sleep.SleepSource.WATCH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Gate: SPEC Sleep tracking — source priority (HC from other apps > watch > phone > anchors),
 * 20-min onset rule, 5-min awakening rule, dismiss cap, and "never duplicate OHealth's data".
 */
class SessionBuilderTest {
    private val own = "com.trepidity.good"
    private val ohealth = "com.oplus.health"
    private val window = NightWindow.forWakeDate(LocalDate.parse("2026-10-02"), CHICAGO)
    private val noAnchors = Anchors(null, null)

    private fun samples(source: SleepSource, vararg points: Pair<String, Boolean>) =
        points.map { (t, asleep) -> Sample(at("2026-10-${t}"), asleep, source) }

    private fun hc(id: String, pkg: String, start: String, end: String, stages: List<Segment> = emptyList()) =
        ExternalSession(id, at(start), at(end), pkg, stages)

    private fun build(
        external: List<ExternalSession> = emptyList(),
        samples: List<Sample> = emptyList(),
        anchors: Anchors = noAnchors,
    ) = SessionBuilder.build(window, external, samples, anchors, own)

    private val watchNight = samples(WATCH, "01T23:00" to true, "02T06:00" to false)

    @Test
    fun `another app's Health Connect session beats watch samples`() {
        val s = build(listOf(hc("x", ohealth, "2026-10-01T23:30", "2026-10-02T06:30")), watchNight)!!
        assertEquals(HEALTH_CONNECT, s.source)
        assertEquals(ohealth, s.sourcePackage)
        assertEquals("x", s.externalId)
        assertEquals(at("2026-10-01T23:30"), s.start)
    }

    @Test
    fun `GOOD's own Health Connect session is ignored so it never replaces the derived one`() {
        val s = build(listOf(hc("mine", own, "2026-10-01T21:00", "2026-10-02T09:00")), watchNight)!!
        assertEquals(WATCH, s.source)
        assertNull(s.externalId)
    }

    @Test
    fun `the external session overlapping the window most is chosen`() {
        val nap = hc("nap", ohealth, "2026-10-01T18:30", "2026-10-01T19:30")
        val night = hc("night", ohealth, "2026-10-01T23:00", "2026-10-02T07:00")
        assertEquals("night", build(listOf(nap, night))!!.externalId)
    }

    @Test
    fun `external AWAKE stages count as awakenings from 5 minutes, not 4`() {
        val stages = listOf(
            Segment(at("2026-10-01T23:00"), at("2026-10-02T01:00"), LIGHT),
            Segment(at("2026-10-02T01:00"), at("2026-10-02T01:04"), AWAKE),
            Segment(at("2026-10-02T01:04"), at("2026-10-02T03:00"), DEEP),
            Segment(at("2026-10-02T03:00"), at("2026-10-02T03:05"), AWAKE),
            Segment(at("2026-10-02T03:05"), at("2026-10-02T06:00"), LIGHT),
        )
        val s = build(listOf(hc("x", ohealth, "2026-10-01T23:00", "2026-10-02T06:00", stages)))!!
        assertEquals(1, s.awakenings)
        assertEquals(7 * 60 - 9, s.totalSleepMin)
    }

    @Test
    fun `a 15-minute doze followed by waking does not start the night`() {
        val s = build(samples = samples(WATCH,
            "01T22:00" to true, "01T22:15" to false,
            "01T22:40" to true, "02T06:00" to false,
        ))!!
        assertEquals(at("2026-10-01T22:40"), s.start)
    }

    @Test
    fun `an asleep run of exactly 20 minutes marks onset`() {
        val s = build(samples = samples(WATCH,
            "01T22:00" to true, "01T22:20" to false,
            "01T22:40" to true, "02T06:00" to false,
        ))!!
        assertEquals(at("2026-10-01T22:00"), s.start)
    }

    @Test
    fun `sample awake runs count as awakenings from 5 minutes, not 4, and are not sleep`() {
        val s = build(samples = samples(PHONE,
            "01T23:00" to true,
            "02T01:00" to false, "02T01:04" to true,
            "02T03:00" to false, "02T03:05" to true,
            "02T06:00" to false,
        ))!!
        assertEquals(1, s.awakenings)
        assertEquals(at("2026-10-02T06:00"), s.end)
        assertEquals(7 * 60 - 9, s.totalSleepMin)
    }

    @Test
    fun `wake is capped at dismiss and later samples are ignored`() {
        val s = build(
            samples = samples(WATCH, "01T23:00" to true, "02T07:00" to false, "02T07:30" to true),
            anchors = Anchors(bedtime = null, dismissedAt = at("2026-10-02T06:15")),
        )!!
        assertEquals(at("2026-10-02T06:15"), s.end)
        assertEquals(listOf(ASLEEP), s.segments.map { it.kind })
    }

    @Test
    fun `samples before the bed button are ignored`() {
        val s = build(
            samples = samples(WATCH, "01T20:00" to true, "01T21:00" to false, "01T23:00" to true, "02T06:00" to false),
            anchors = Anchors(bedtime = at("2026-10-01T22:30"), dismissedAt = null),
        )!!
        assertEquals(at("2026-10-01T23:00"), s.start)
    }

    @Test
    fun `watch samples win over phone samples`() {
        val phone = samples(PHONE, "01T22:00" to true, "02T07:00" to false)
        val s = build(samples = phone + watchNight)!!
        assertEquals(WATCH, s.source)
        assertEquals(at("2026-10-01T23:00"), s.start)
    }

    @Test
    fun `bed and dismiss alone give an anchor session, one anchor alone gives none`() {
        val both = build(anchors = Anchors(at("2026-10-01T22:30"), at("2026-10-02T06:30")))!!
        assertEquals(ANCHORS, both.source)
        assertEquals(8 * 60, both.totalSleepMin)
        assertNull(build(anchors = Anchors(at("2026-10-01T22:30"), null)))
        assertNull(build(anchors = Anchors(null, at("2026-10-02T06:30"))))
    }
}
