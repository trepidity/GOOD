package com.trepidity.good.model

import kotlinx.serialization.Serializable

/** The two devices GOOD runs on. */
@Serializable
enum class Device { PHONE, WATCH }

/** Which device(s) a stage is meant for. */
@Serializable
enum class DeviceTarget { PHONE, WATCH, BOTH }

/** Where the audible stage plays. AUTO = watch when worn and reachable, otherwise phone. */
@Serializable
enum class SoundTarget { AUTO, PHONE, WATCH, BOTH }

@Serializable
enum class StageType { LIGHT, HAPTIC, SOUND, ESCALATE }

/**
 * One step of a wake profile.
 *
 * @param offsetSec start time relative to the alarm time T (negative = before T).
 * @param rampSec how long the stage takes to reach full strength.
 * @param params stage-specific knobs, see [StageParams].
 */
@Serializable
data class Stage(
    val type: StageType,
    val device: DeviceTarget,
    val offsetSec: Int,
    val rampSec: Int,
    val params: Map<String, String> = emptyMap(),
)

object StageParams {
    /** SOUND: starting gain, 0.0–1.0. */
    const val START_GAIN = "startGain"
    /** ESCALATE: seconds after T at which the phone joins when the watch is carrying the sound. */
    const val PHONE_JOIN_OFFSET_SEC = "phoneJoinOffsetSec"
}

@Serializable
data class WakeProfile(
    val id: String,
    val name: String,
    val stages: List<Stage>,
    val autoSilenceMinutes: Int = 20,
) {
    // Deliberately no snooze: GOOD never offers one (product decision, see docs/SPEC.md).

    companion object {
        val GENTLE = WakeProfile(
            id = "gentle",
            name = "Gentle",
            stages = listOf(
                Stage(StageType.LIGHT, DeviceTarget.PHONE, offsetSec = -600, rampSec = 600),
                Stage(StageType.HAPTIC, DeviceTarget.WATCH, offsetSec = -180, rampSec = 180),
                Stage(StageType.SOUND, DeviceTarget.BOTH, offsetSec = 0, rampSec = 300, params = mapOf(StageParams.START_GAIN to "0.05")),
                Stage(StageType.ESCALATE, DeviceTarget.BOTH, offsetSec = 300, rampSec = 0, params = mapOf(StageParams.PHONE_JOIN_OFFSET_SEC to "600")),
            ),
        )

        val QUICK = WakeProfile(
            id = "quick",
            name = "Quick",
            stages = listOf(
                Stage(StageType.LIGHT, DeviceTarget.PHONE, offsetSec = -180, rampSec = 180),
                Stage(StageType.HAPTIC, DeviceTarget.WATCH, offsetSec = -60, rampSec = 60),
                Stage(StageType.SOUND, DeviceTarget.BOTH, offsetSec = 0, rampSec = 60, params = mapOf(StageParams.START_GAIN to "0.1")),
                Stage(StageType.ESCALATE, DeviceTarget.BOTH, offsetSec = 180, rampSec = 0, params = mapOf(StageParams.PHONE_JOIN_OFFSET_SEC to "300")),
            ),
        )

        val HEAVY_SLEEPER = WakeProfile(
            id = "heavy",
            name = "Heavy sleeper",
            stages = listOf(
                Stage(StageType.LIGHT, DeviceTarget.PHONE, offsetSec = -600, rampSec = 600),
                Stage(StageType.HAPTIC, DeviceTarget.WATCH, offsetSec = -180, rampSec = 120),
                Stage(StageType.SOUND, DeviceTarget.BOTH, offsetSec = 0, rampSec = 120, params = mapOf(StageParams.START_GAIN to "0.15")),
                Stage(StageType.ESCALATE, DeviceTarget.BOTH, offsetSec = 120, rampSec = 0, params = mapOf(StageParams.PHONE_JOIN_OFFSET_SEC to "240")),
            ),
        )

        val PRESETS = listOf(GENTLE, QUICK, HEAVY_SLEEPER)
    }
}

/**
 * A user alarm. Times are local wall-clock; [repeatDays] is a bitmask with Monday = bit 0 … Sunday = bit 6
 * (0 = one-shot). [skipNextDate] is an ISO local date (yyyy-MM-dd) to skip once.
 */
@Serializable
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val repeatDays: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
    val profileId: String = WakeProfile.GENTLE.id,
    val soundTarget: SoundTarget = SoundTarget.AUTO,
    val skipNextDate: String? = null,
)

@Serializable
enum class InstanceState { SCHEDULED, FIRING, DISMISSED, SILENCED, SKIPPED;
    val isTerminal: Boolean get() = this == DISMISSED || this == SILENCED || this == SKIPPED
}

/** One occurrence of an alarm. [scheduledAtEpochMs] is the alarm time T. */
@Serializable
data class AlarmInstance(
    val id: String,
    val alarmId: Long,
    val scheduledAtEpochMs: Long,
    val state: InstanceState = InstanceState.SCHEDULED,
    val currentStage: StageType? = null,
    val dismissedAtEpochMs: Long? = null,
    val dismissedOn: Device? = null,
)

/** What the phone publishes to the watch at `/schedule`. */
@Serializable
data class ScheduleSnapshot(
    val version: Long,
    val generatedAtEpochMs: Long,
    val entries: List<ScheduleEntry>,
)

@Serializable
data class ScheduleEntry(
    val instance: AlarmInstance,
    val profile: WakeProfile,
    val soundTarget: SoundTarget,
    val label: String = "",
)

/** Dismiss / bedtime messages exchanged over the Data Layer. */
@Serializable
data class Command(
    val instanceId: String,
    val sentAtEpochMs: Long,
    val from: Device,
)
