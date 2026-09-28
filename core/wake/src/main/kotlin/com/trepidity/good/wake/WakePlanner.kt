package com.trepidity.good.wake

import com.trepidity.good.model.Device
import com.trepidity.good.model.DeviceTarget
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.Stage
import com.trepidity.good.model.StageParams
import com.trepidity.good.model.StageType
import com.trepidity.good.model.WakeProfile
import java.time.Duration
import java.time.Instant

/** A stage resolved to a concrete start time on one device. */
data class PlannedStage(
    val type: StageType,
    val startAt: Instant,
    val rampSec: Int,
    val params: Map<String, String> = emptyMap(),
)

/**
 * Turns a wake profile into the list of stages one device must run.
 *
 * Both devices call this with the same alarm time T, so they agree on stage times without talking.
 * [watchAvailable] means "the watch is worn and reachable": the phone gets it from the `/health` ping,
 * the watch from its own off-body sensor.
 */
object WakePlanner {

    fun plan(
        fireAt: Instant,
        profile: WakeProfile,
        device: Device,
        soundTarget: SoundTarget,
        watchAvailable: Boolean,
    ): List<PlannedStage> {
        val sound = resolveSound(soundTarget, watchAvailable)
        return profile.stages.mapNotNull { stage ->
            val start = fireAt.plusSeconds(stage.offsetSec.toLong())
            when (stage.type) {
                StageType.LIGHT ->
                    if (covers(stage.device, device)) stage.toPlanned(start) else null

                StageType.HAPTIC -> when {
                    covers(stage.device, device) -> stage.toPlanned(start)
                    // Watch can't do it: the phone buzzes instead.
                    device == Device.PHONE && stage.device == DeviceTarget.WATCH && !watchAvailable -> stage.toPlanned(start)
                    else -> null
                }

                StageType.SOUND ->
                    if (covers(sound, device)) stage.toPlanned(start) else null

                StageType.ESCALATE -> {
                    val phoneJoinsLate = device == Device.PHONE && sound == DeviceTarget.WATCH
                    if (phoneJoinsLate) {
                        val join = stage.params[StageParams.PHONE_JOIN_OFFSET_SEC]?.toLongOrNull() ?: 600L
                        stage.toPlanned(fireAt.plusSeconds(join))
                    } else if (device == Device.WATCH && !watchAvailable) {
                        null
                    } else {
                        stage.toPlanned(start)
                    }
                }
            }
        }.sortedBy { it.startAt }
    }

    /** Where the sound stage really plays, after applying the AUTO rule and the watch-missing fallback. */
    fun resolveSound(target: SoundTarget, watchAvailable: Boolean): DeviceTarget = when (target) {
        SoundTarget.AUTO -> if (watchAvailable) DeviceTarget.WATCH else DeviceTarget.PHONE
        SoundTarget.PHONE -> DeviceTarget.PHONE
        SoundTarget.WATCH -> if (watchAvailable) DeviceTarget.WATCH else DeviceTarget.PHONE
        SoundTarget.BOTH -> if (watchAvailable) DeviceTarget.BOTH else DeviceTarget.PHONE
    }

    fun firstStageAt(plan: List<PlannedStage>): Instant? = plan.minOfOrNull { it.startAt }

    fun silenceAt(fireAt: Instant, profile: WakeProfile): Instant =
        fireAt.plus(Duration.ofMinutes(profile.autoSilenceMinutes.toLong()))

    /** Wake-lock length for a ring service starting at [serviceStart]: until auto-silence plus 2 min (REVIEW R2). */
    fun holdMs(serviceStart: Instant, fireAt: Instant, profile: WakeProfile): Long =
        Duration.between(serviceStart, silenceAt(fireAt, profile)).plusMinutes(2).toMillis().coerceAtLeast(60_000L)

    /**
     * Squeeze a plan around [anchor] by [factor] (e.g. 0.05) for the 60-second "Preview" button.
     */
    fun compress(plan: List<PlannedStage>, anchor: Instant, factor: Double): List<PlannedStage> =
        plan.map {
            val deltaMs = Duration.between(anchor, it.startAt).toMillis()
            it.copy(
                startAt = anchor.plusMillis((deltaMs * factor).toLong()),
                rampSec = maxOf(1, (it.rampSec * factor).toInt()),
            )
        }

    private fun covers(target: DeviceTarget, device: Device): Boolean = when (target) {
        DeviceTarget.BOTH -> true
        DeviceTarget.PHONE -> device == Device.PHONE
        DeviceTarget.WATCH -> device == Device.WATCH
    }

    private fun Stage.toPlanned(start: Instant) = PlannedStage(type, start, rampSec, params)
}
