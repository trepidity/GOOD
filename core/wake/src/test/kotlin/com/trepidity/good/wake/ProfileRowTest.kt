package com.trepidity.good.wake

import com.trepidity.good.model.StageType
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate: F3 (configurable stages) with the PRO editor's limits; an edit must never leave a profile that can't ring. */
class ProfileRowTest {
    @Test
    fun `full volume can't be pushed past auto-silence, or the alarm would stop before it escalates`() {
        val p = ProfileRow.FULL.set(WakeProfile.GENTLE, 60)
        val escalate = p.stages.first { it.type == StageType.ESCALATE }.offsetSec / 60
        assertTrue(escalate < p.autoSilenceMinutes)
    }

    @Test
    fun `shortening auto-silence pulls full volume in with it`() {
        val p = ProfileRow.SIL.set(WakeProfile.GENTLE, 5)
        assertEquals(5, p.autoSilenceMinutes)
        assertEquals(4, ProfileRow.FULL.get(p))
    }

    @Test
    fun `light lead sets both the start before T and the ramp length`() {
        val light = ProfileRow.LIGHT.set(WakeProfile.GENTLE, 20).stages.first { it.type == StageType.LIGHT }
        assertEquals(-1200, light.offsetSec)
        assertEquals(1200, light.rampSec)
    }
}
