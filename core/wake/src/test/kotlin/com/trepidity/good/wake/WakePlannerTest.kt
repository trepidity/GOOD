package com.trepidity.good.wake

import com.trepidity.good.model.Device
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.StageType
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WakePlannerTest {
    private val t = Instant.parse("2026-10-01T11:30:00Z")
    private val gentle = WakeProfile.GENTLE

    private fun List<PlannedStage>.types() = map { it.type }
    private fun List<PlannedStage>.at(type: StageType) = first { it.type == type }.startAt

    @Test
    fun `phone with watch available gets light and a late safety-net escalate only`() {
        val plan = WakePlanner.plan(t, gentle, Device.PHONE, SoundTarget.AUTO, watchAvailable = true)
        assertEquals(listOf(StageType.LIGHT, StageType.ESCALATE), plan.types())
        assertEquals(t.minusSeconds(600), plan.at(StageType.LIGHT))
        assertEquals(t.plusSeconds(600), plan.at(StageType.ESCALATE))
    }

    @Test
    fun `watch with AUTO sound gets haptics, sound and escalate`() {
        val plan = WakePlanner.plan(t, gentle, Device.WATCH, SoundTarget.AUTO, watchAvailable = true)
        assertEquals(listOf(StageType.HAPTIC, StageType.SOUND, StageType.ESCALATE), plan.types())
        assertEquals(t.minusSeconds(180), plan.at(StageType.HAPTIC))
        assertEquals(t, plan.at(StageType.SOUND))
        assertEquals(t.plusSeconds(300), plan.at(StageType.ESCALATE))
    }

    @Test
    fun `phone takes over haptics and sound when watch is missing`() {
        val plan = WakePlanner.plan(t, gentle, Device.PHONE, SoundTarget.AUTO, watchAvailable = false)
        assertEquals(listOf(StageType.LIGHT, StageType.HAPTIC, StageType.SOUND, StageType.ESCALATE), plan.types())
        assertEquals(t.plusSeconds(300), plan.at(StageType.ESCALATE))
    }

    @Test
    fun `phone-only sound keeps escalate at T plus 5`() {
        val plan = WakePlanner.plan(t, gentle, Device.PHONE, SoundTarget.PHONE, watchAvailable = true)
        assertTrue(StageType.SOUND in plan.types())
        assertEquals(t.plusSeconds(300), plan.at(StageType.ESCALATE))
        val watch = WakePlanner.plan(t, gentle, Device.WATCH, SoundTarget.PHONE, watchAvailable = true)
        assertEquals(listOf(StageType.HAPTIC, StageType.ESCALATE), watch.types())
    }

    @Test
    fun `compress squeezes around the anchor`() {
        val plan = WakePlanner.plan(t, gentle, Device.PHONE, SoundTarget.AUTO, watchAvailable = false)
        val squeezed = WakePlanner.compress(plan, t, 0.05)
        assertEquals(t.minusSeconds(30), squeezed.at(StageType.LIGHT))
        assertEquals(t.plusSeconds(15), squeezed.at(StageType.ESCALATE))
    }
}
