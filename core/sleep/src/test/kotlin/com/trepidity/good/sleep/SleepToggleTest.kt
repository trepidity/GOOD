package com.trepidity.good.sleep

import org.junit.Assert.assertEquals
import org.junit.Test

/** Gate: I'M UP spec — the BED/UP toggle is UP only after tonight's bed press, and never gets stuck on UP. */
class SleepToggleTest {
    private fun next(bed: String?, wake: String?, now: String) =
        SleepToggle.next(bed?.let(::at), wake?.let(::at), at(now), CHICAGO)

    @Test fun `a bed press tonight with no wake since shows UP`() =
        assertEquals(SleepAction.UP, next("2026-09-30T23:00", null, "2026-10-01T05:00"))

    @Test fun `a wake after the bed press shows BED`() =
        assertEquals(SleepAction.BED, next("2026-09-30T23:00", "2026-10-01T06:00", "2026-10-01T06:01"))

    @Test fun `last night's bed press without a wake does not carry into tonight`() =
        assertEquals(SleepAction.BED, next("2026-09-29T23:00", null, "2026-09-30T22:00"))

    @Test fun `between 14 00 and 18 00 it is always BED`() =
        assertEquals(SleepAction.BED, next("2026-10-01T13:30", null, "2026-10-01T15:00"))

    /** Gate: I'M UP spec state rule — yesterday's wake must not hide tonight's bed press. */
    @Test fun `a wake from before tonight's bed press does not hide it`() =
        assertEquals(SleepAction.UP, next("2026-09-30T23:00", "2026-09-30T07:00", "2026-10-01T05:00"))

    /** Gate: I'M UP spec state rule — a wake at the same instant as the bed press closes the night. */
    @Test fun `a wake at the same instant as the bed press shows BED`() =
        assertEquals(SleepAction.BED, next("2026-09-30T23:00", "2026-09-30T23:00", "2026-10-01T05:00"))
}
