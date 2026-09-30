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

/** The audible pattern. CHIME = soft two-note chime; CLASSIC = the four-beep digital-watch alarm. Both ramp from ~5%. */
@Serializable
enum class Tone { CHIME, CLASSIC }

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
    val tone: Tone = Tone.CHIME,
)

@Serializable
enum class InstanceState { SCHEDULED, FIRING, DISMISSED, SILENCED, SKIPPED, CANCELLED;
    /** CANCELLED = closed because its channel was edited or disarmed, not because anything happened. */
    val isTerminal: Boolean get() = this != SCHEDULED && this != FIRING
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
    /** When the first stage started; null if none did (e.g. closed by I'M UP before ringing). */
    val firstStageAtEpochMs: Long? = null,
    /** The stage that was running when it was dismissed; null for a dismiss before any stage. */
    val dismissedAtStage: StageType? = null,
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
    val tone: Tone = Tone.CHIME,
)

/** Dismiss messages exchanged over the Data Layer, and the `/instance/{id}/state` catch-up item. */
@Serializable
data class Command(
    val instanceId: String,
    val sentAtEpochMs: Long,
    val from: Device,
    /** The state the receiver closes the occurrence in: DISMISSED, or CANCELLED when its channel was disarmed or edited. */
    val state: InstanceState = InstanceState.DISMISSED,
    /**
     * The sender's stage when it was dismissed; the receiver records it as `dismissedAtStage`, since its own copy
     * may be a stage behind (or none, when only the sender was ringing). Null = unknown; old payloads decode as null.
     */
    val stage: StageType? = null,
)

/** Watch → phone: arm or disarm channel [alarmId] (`/cmd/toggle`). */
@Serializable
data class ToggleCommand(val alarmId: Long, val sentAtEpochMs: Long)

/** Watch → phone: the bed button was pressed (`/sleep/bedtime`). */
@Serializable
data class BedtimeMessage(val atEpochMs: Long)

/** Watch → phone: I'M UP was pressed (`/sleep/wake`). */
@Serializable
data class WakeAnchorMessage(val atEpochMs: Long)

/** Watch → phone: an asleep/awake transition from Health Services passive monitoring (`/sleep/signal`). */
@Serializable
data class SleepSignalMessage(val atEpochMs: Long, val asleep: Boolean)

/** Watch → phone reply to the `/health` ping. */
@Serializable
data class HealthReply(val worn: Boolean, val sentAtEpochMs: Long)

/** Phone → watch (`/sleep/summary`): what the tile and complications show. */
@Serializable
data class SleepSummary(
    /** ISO local date the night belongs to (the wake date), or null before the first night. */
    val wakeDate: String?,
    val totalSleepMin: Int?,
    val bedtimeEpochMs: Long?,
    val wakeEpochMs: Long?,
    val goalMin: Int,
    val fromHealthConnect: Boolean,
    /** The latest bed-button press and wake anchor (dismiss or I'M UP) the phone knows of; they drive the watch's BED/UP toggle. */
    val lastBedAnchorEpochMs: Long? = null,
    val lastWakeAnchorEpochMs: Long? = null,
)
