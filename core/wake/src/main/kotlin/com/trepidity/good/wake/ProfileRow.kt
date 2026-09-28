package com.trepidity.good.wake

import com.trepidity.good.model.Stage
import com.trepidity.good.model.StageType
import com.trepidity.good.model.WakeProfile

/**
 * The PRO mode's editable rows, in minutes (SPEC UX: INT 1 LIGHT, INT 2 BUZZ, INT 3 TONE, INT 4 FULL, SIL).
 * [set] clamps to [range] and keeps the profile ringable: full volume always starts before auto-silence.
 */
enum class ProfileRow(val code: String, val range: IntRange) {
    LIGHT("LIGHT", 0..30),
    BUZZ("BUZZ", 0..15),
    TONE("TONE", 1..15),
    FULL("FULL", 1..29),
    SIL("SIL", 5..30);

    fun get(p: WakeProfile): Int = when (this) {
        LIGHT -> p.stage(StageType.LIGHT)?.let { -it.offsetSec / 60 } ?: 0
        BUZZ -> p.stage(StageType.HAPTIC)?.let { -it.offsetSec / 60 } ?: 0
        TONE -> p.stage(StageType.SOUND)?.let { it.rampSec / 60 } ?: 1
        FULL -> p.stage(StageType.ESCALATE)?.let { it.offsetSec / 60 } ?: 5
        SIL -> p.autoSilenceMinutes
    }

    fun set(p: WakeProfile, minutes: Int): WakeProfile {
        val v = minutes.coerceIn(range)
        return when (this) {
            LIGHT -> p.edit(StageType.LIGHT) { it.copy(offsetSec = -v * 60, rampSec = v * 60) }
            BUZZ -> p.edit(StageType.HAPTIC) { it.copy(offsetSec = -v * 60, rampSec = v * 60) }
            TONE -> p.edit(StageType.SOUND) { it.copy(rampSec = v * 60) }
            FULL -> p.edit(StageType.ESCALATE) { it.copy(offsetSec = minOf(v, p.autoSilenceMinutes - 1) * 60) }
            SIL -> p.copy(autoSilenceMinutes = v).let { q -> if (FULL.get(q) >= v) FULL.set(q, v - 1) else q }
        }
    }

    private companion object {
        fun WakeProfile.stage(type: StageType) = stages.firstOrNull { it.type == type }
        fun WakeProfile.edit(type: StageType, f: (Stage) -> Stage) = copy(stages = stages.map { if (it.type == type) f(it) else it })
    }
}
