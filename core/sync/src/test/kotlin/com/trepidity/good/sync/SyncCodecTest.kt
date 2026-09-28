package com.trepidity.good.sync

import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.WakeProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate: SPEC Architecture — the watch keeps a snapshot only if it is newer (version is monotonic). */
class SyncCodecTest {
    private val snapshot = ScheduleSnapshot(
        version = 7,
        generatedAtEpochMs = 1_790_000_000_000,
        entries = listOf(ScheduleEntry(AlarmInstance("i1", 1, 1_790_050_000_000), WakeProfile.GENTLE, SoundTarget.AUTO, "Work")),
    )

    @Test
    fun `a stale or replayed snapshot never replaces a newer one`() {
        assertTrue(SyncCodec.shouldApply(snapshot, null))
        assertFalse(SyncCodec.shouldApply(snapshot.copy(version = 6), snapshot))
        assertTrue(SyncCodec.shouldApply(snapshot.copy(version = 8), snapshot))
    }
}
