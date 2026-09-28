package com.trepidity.good.wake

import com.trepidity.good.model.Alarm
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.WakeProfile
import java.time.Instant
import java.time.ZoneId

object InstanceIds {
    /** Deterministic, so re-running the scheduler for the same occurrence keeps its identity and state. */
    fun of(alarmId: Long, fireAt: Instant): String = "a$alarmId-${fireAt.toEpochMilli()}"
}

/** One alarm channel as the scheduler sees it: the alarm, its profile and its most recent occurrence. */
data class Channel(val alarm: Alarm, val profile: WakeProfile, val last: AlarmInstance?)

/**
 * @param active the occurrence to keep registered (new, unchanged, or still ringing); null = nothing to register.
 * @param missed the previous occurrence, closed as SILENCED because its auto-silence time passed without a dismiss.
 */
data class ChannelDecision(val active: AlarmInstance?, val missed: AlarmInstance?)

/**
 * Rolls one channel forward. The next occurrence is searched after max(now, previous T), so an early
 * dismiss never re-arms the same morning (REVIEW R5). A ringing occurrence is never touched.
 */
object ScheduleBuilder {

    fun decide(channel: Channel, now: Instant, zone: ZoneId): ChannelDecision {
        val (alarm, profile, last) = channel
        var missed: AlarmInstance? = null
        var after = now

        if (last != null) {
            val lastT = Instant.ofEpochMilli(last.scheduledAtEpochMs)
            val live = !last.state.isTerminal && now.isBefore(WakePlanner.silenceAt(lastT, profile))
            when {
                live && last.state == InstanceState.FIRING -> return ChannelDecision(last, null)
                live -> Unit // SCHEDULED and still ahead: recompute below so edits take effect
                !last.state.isTerminal -> {
                    missed = last.copy(state = InstanceState.SILENCED, currentStage = null)
                    after = maxOf(now, lastT)
                }
                else -> after = maxOf(now, lastT)
            }
        }

        val fireAt = NextOccurrence.nextFireTime(alarm, after, zone) ?: return ChannelDecision(null, missed)
        val id = InstanceIds.of(alarm.id, fireAt)
        if (last != null && last.id == id && !last.state.isTerminal) return ChannelDecision(last, missed)
        return ChannelDecision(AlarmInstance(id, alarm.id, fireAt.toEpochMilli()), missed)
    }
}
