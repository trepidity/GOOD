package com.trepidity.good.sleep

import com.trepidity.good.sleep.SleepSource.ANCHORS
import com.trepidity.good.sleep.SleepSource.HEALTH_CONNECT
import com.trepidity.good.sleep.SleepSource.PHONE
import com.trepidity.good.sleep.SleepSource.WATCH
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate: SPEC Sleep tracking steps 3 and 6 — HC replaces provisional values; edited sessions are never overwritten. */
class ShouldReplaceTest {
    private fun draft(source: SleepSource) =
        SessionDraft(at("2026-10-01T23:00"), at("2026-10-02T06:00"), source, null, null, emptyList(), 0)

    @Test
    fun `an edited session is never replaced, even by Health Connect`() {
        assertFalse(SessionBuilder.shouldReplace(ANCHORS, existingEdited = true, draft(HEALTH_CONNECT)))
    }

    @Test
    fun `a Health Connect session is never replaced by watch, phone or anchors`() {
        for (s in listOf(WATCH, PHONE, ANCHORS)) {
            assertFalse(s.name, SessionBuilder.shouldReplace(HEALTH_CONNECT, existingEdited = false, draft(s)))
        }
    }

    @Test
    fun `the provisional session is replaced by a later sync of equal or higher priority`() {
        assertTrue(SessionBuilder.shouldReplace(ANCHORS, existingEdited = false, draft(ANCHORS)))
        assertTrue(SessionBuilder.shouldReplace(ANCHORS, existingEdited = false, draft(PHONE)))
        assertTrue(SessionBuilder.shouldReplace(WATCH, existingEdited = false, draft(HEALTH_CONNECT)))
    }
}
