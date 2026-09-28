package com.trepidity.good.sync

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gate: REVIEW R4 — a snapshot from a phone that hasn't heard of the watch's dismiss must not re-arm it. */
class ScheduleMergeTest {
    private fun entry(id: String, state: InstanceState = InstanceState.SCHEDULED) =
        ScheduleEntry(AlarmInstance(id, 1, 1_790_050_000_000, state), WakeProfile.GENTLE, SoundTarget.AUTO)

    @Test
    fun `a locally dismissed occurrence stays dismissed when a stale snapshot says scheduled`() {
        val dismissed = entry("a1").let { it.copy(instance = it.instance.copy(state = InstanceState.DISMISSED, dismissedOn = Device.WATCH)) }
        val local = ScheduleSnapshot(1, 0, listOf(dismissed))
        val incoming = ScheduleSnapshot(2, 0, listOf(entry("a1"), entry("a2")))
        val merged = ScheduleMerge.merge(incoming, local)
        assertEquals(InstanceState.DISMISSED, merged.entries.first { it.instance.id == "a1" }.instance.state)
        assertEquals(InstanceState.SCHEDULED, merged.entries.first { it.instance.id == "a2" }.instance.state)
    }

    @Test
    fun `a terminal state from the phone wins over the watch's pending copy`() {
        val local = ScheduleSnapshot(1, 0, listOf(entry("a1")))
        val incoming = ScheduleSnapshot(2, 0, listOf(entry("a1", InstanceState.DISMISSED)))
        assertEquals(InstanceState.DISMISSED, ScheduleMerge.merge(incoming, local).entries.single().instance.state)
    }
}
