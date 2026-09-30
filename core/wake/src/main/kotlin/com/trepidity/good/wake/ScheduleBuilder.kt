package com.trepidity.good.wake

import com.trepidity.good.model.Alarm
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.WakeProfile
import java.time.Instant
import java.time.LocalTime
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
 * @param skipped the previous occurrence, closed as SKIPPED because its date is the channel's skip date.
 * @param cancelled the previous occurrence, closed as CANCELLED because the channel was disarmed or its time moved.
 */
data class ChannelDecision(
    val active: AlarmInstance?,
    val missed: AlarmInstance? = null,
    val skipped: AlarmInstance? = null,
    val cancelled: AlarmInstance? = null,
)

/**
 * Rolls one channel forward. The next occurrence is searched after max(now, previous T), so an early
 * dismiss never re-arms the same morning (REVIEW R5). A ringing occurrence is kept unless its channel was
 * disarmed or moved, which cancels it (#6).
 */
object ScheduleBuilder {

    /**
     * The channel's current occurrence: the latest by alarm time, ignoring CANCELLED rows. A cancelled row
     * can lie in the future (7:00 moved to 6:30), and treating it as current would roll past 6:30.
     */
    fun current(rows: List<AlarmInstance>): AlarmInstance? =
        rows.filter { it.state != InstanceState.CANCELLED }.maxByOrNull { it.scheduledAtEpochMs }

    fun decide(channel: Channel, now: Instant, zone: ZoneId): ChannelDecision {
        val (alarm, profile, last) = channel
        var missed: AlarmInstance? = null
        var skipped: AlarmInstance? = null
        var cancelled: AlarmInstance? = null
        var open: AlarmInstance? = null
        var after = now

        if (last != null) {
            val lastT = Instant.ofEpochMilli(last.scheduledAtEpochMs)
            val live = !last.state.isTerminal && now.isBefore(WakePlanner.silenceAt(lastT, profile))
            val overdue = live && !now.isBefore(lastT)
            val unchanged = alarm.enabled && lastT.atZone(zone).toLocalTime() == LocalTime.of(alarm.hour, alarm.minute)
            val onSkipDate = lastT.atZone(zone).toLocalDate().toString() == alarm.skipNextDate
            when {
                live && last.state == InstanceState.FIRING && unchanged -> return ChannelDecision(last)
                live && last.state == InstanceState.FIRING -> {
                    cancelled = last.closedAs(InstanceState.CANCELLED)
                    after = maxOf(now, lastT)
                }
                live && onSkipDate -> {
                    skipped = last.closedAs(InstanceState.SKIPPED)
                    after = lastT
                }
                // Past T but its alarm never arrived (dropped, or a reboot): keep it so it is re-registered and
                // rings now, ramps resumed, instead of silently rolling to tomorrow.
                overdue && unchanged -> return ChannelDecision(last)
                live -> open = last // still ahead, or the channel was edited since: recompute below
                !last.state.isTerminal -> {
                    missed = last.closedAs(InstanceState.SILENCED)
                    after = maxOf(now, lastT)
                }
                else -> after = maxOf(now, lastT)
            }
        }

        val fireAt = NextOccurrence.nextFireTime(alarm, after, zone)
        val id = fireAt?.let { InstanceIds.of(alarm.id, it) }
        if (open != null && open.id == id) return ChannelDecision(open)
        if (open != null) cancelled = open.closedAs(InstanceState.CANCELLED)
        val active = fireAt?.let { AlarmInstance(id!!, alarm.id, it.toEpochMilli()) }
        return ChannelDecision(active, missed, skipped, cancelled)
    }

    private fun AlarmInstance.closedAs(state: InstanceState) = copy(state = state, currentStage = null)
}
