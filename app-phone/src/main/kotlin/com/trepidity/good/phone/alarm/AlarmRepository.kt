package com.trepidity.good.phone.alarm

import android.content.Context
import com.trepidity.good.model.Alarm
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.ScheduleSnapshot
import com.trepidity.good.model.WakeProfile
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.data.AlarmEntity
import com.trepidity.good.phone.data.GoodDatabase
import com.trepidity.good.phone.data.InstanceEntity
import com.trepidity.good.phone.data.ProfileEntity
import com.trepidity.good.phone.sleep.BedtimeReminderScheduler
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.wake.Channel
import com.trepidity.good.wake.ScheduleBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** The four alarm channels, the wake profiles, and the one idempotent [rescheduleAll] every path goes through. */
class AlarmRepository(private val context: Context, private val db: GoodDatabase) {
    private val mutex = Mutex()

    val alarms: Flow<List<Alarm>> = db.alarms().observe().map { list -> list.map { it.toModel() } }
    val profiles: Flow<List<WakeProfile>> = db.profiles().observe().map { list -> list.map { it.toModel() } }

    /** Seeds AL1–AL4 (disarmed, 6:30) and the preset profiles on first launch. */
    suspend fun seed() {
        db.profiles().insertIfMissing(WakeProfile.PRESETS.map(ProfileEntity::of))
        db.alarms().insertIfMissing((1L..CHANNELS).map { AlarmEntity.of(Alarm(id = it, hour = 6, minute = 30, enabled = false)) })
    }

    suspend fun save(alarm: Alarm, reason: String = "edit") {
        db.alarms().upsert(AlarmEntity.of(alarm))
        EventLog.log(context, "ALARM_SAVED", "AL${alarm.id} ${alarm.hour}:${alarm.minute} armed=${alarm.enabled}")
        rescheduleAll(reason)
    }

    suspend fun setArmed(channel: Long, armed: Boolean) {
        val a = db.alarms().get(channel)?.toModel() ?: return
        save(a.copy(enabled = armed, skipNextDate = null), if (armed) "arm" else "disarm")
    }

    suspend fun toggle(channel: Long) {
        val a = db.alarms().get(channel)?.toModel() ?: return
        setArmed(channel, !a.enabled)
    }

    suspend fun saveProfile(profile: WakeProfile) {
        db.profiles().upsert(ProfileEntity.of(profile))
        rescheduleAll("profile")
    }

    /**
     * Persists an occurrence's state. The first transition into a terminal state disarms a one-shot alarm
     * (REVIEW R5); later duplicates (e.g. the dismiss message and the state item both arriving) are ignored.
     */
    suspend fun markInstance(instance: AlarmInstance) {
        val previous = db.instances().get(instance.id)?.toModel()
        if (previous != null && previous.state.isTerminal) return
        db.instances().upsert(InstanceEntity.of(instance))
        if (instance.state.isTerminal && instance.alarmId > 0) {
            val alarm = db.alarms().get(instance.alarmId)?.toModel()
            if (alarm != null && alarm.repeatDays == 0 && alarm.enabled) db.alarms().upsert(AlarmEntity.of(alarm.copy(enabled = false)))
        }
    }

    /**
     * Rolls every channel forward, persists the result, registers the OS alarms, writes the device-protected
     * snapshot and publishes it to the watch. Safe to call any number of times, from any trigger.
     */
    suspend fun rescheduleAll(reason: String): ScheduleSnapshot = mutex.withLock {
        seed()
        reconcileFromStore()
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val profiles = db.profiles().all().map { it.toModel() }.associateBy { it.id }
        val entries = mutableListOf<ScheduleEntry>()

        for (alarm in db.alarms().all().map { it.toModel() }) {
            val profile = profiles[alarm.profileId] ?: WakeProfile.GENTLE
            val last = db.instances().latest(alarm.id)?.toModel()
            val decision = ScheduleBuilder.decide(Channel(alarm, profile, last), now, zone)
            decision.missed?.let {
                markInstance(it)
                EventLog.log(context, "MISSED", "${it.id} never dismissed; closed as silenced")
            }
            val active = decision.active
            if (active == null) {
                AlarmScheduler.cancelChannel(context, alarm.id)
                continue
            }
            if (active.id != last?.id) db.instances().upsert(InstanceEntity.of(active))
            // One-shot alarms disarm on their terminal transition, so a re-read keeps this honest.
            val current = db.alarms().get(alarm.id)?.toModel() ?: alarm
            if (!current.enabled && active.state == InstanceState.SCHEDULED) {
                AlarmScheduler.cancelChannel(context, alarm.id)
                continue
            }
            entries += ScheduleEntry(active, profile, alarm.soundTarget, alarm.label.ifBlank { "AL${alarm.id}" }, alarm.tone)
        }

        // Keep a pending or ringing CHK test alarm (channel 0), which lives only in the snapshot. Previews
        // (channel −1) are dropped here, so they never reach the watch.
        val tests = ScheduleStore.load(context)?.entries.orEmpty().filter {
            it.instance.alarmId == 0L && !it.instance.state.isTerminal && it.instance.scheduledAtEpochMs > now.toEpochMilli() - 30 * 60_000
        }
        val snapshot = ScheduleSnapshot(ScheduleStore.nextVersion(context), now.toEpochMilli(), entries + tests)
        ScheduleStore.save(context, snapshot)
        var refused = false
        entries.forEach { if (it.instance.state == InstanceState.SCHEDULED && !AlarmScheduler.schedule(context, it)) refused = true }
        if (refused) EventLog.log(context, "EXACT_ALARM_REFUSED", reason)
        EventLog.log(context, "RESCHEDULED", "$reason · v${snapshot.version} · ${entries.size} armed")

        runCatching { PhoneSync.pushSchedule(context, snapshot) }
            .onFailure { EventLog.log(context, "PUSH_FAILED", it.message.orEmpty()) }
        BedtimeReminderScheduler.update(context, snapshot)
        AppGraph.sleep(context).ensureSleepApi()
        snapshot
    }

    /**
     * Copies occurrence states the ring service or the listener recorded in the device-protected snapshot
     * (possibly while the phone was still locked) into Room.
     */
    private suspend fun reconcileFromStore() {
        val snap = ScheduleStore.load(context) ?: return
        for (entry in snap.entries) {
            if (entry.instance.alarmId <= 0L) continue
            val stored = db.instances().get(entry.instance.id)?.toModel()
            if (stored == null || rank(entry.instance.state) > rank(stored.state)) markInstance(entry.instance)
        }
    }

    private fun rank(state: InstanceState) = when (state) {
        InstanceState.SCHEDULED -> 0
        InstanceState.FIRING -> 1
        else -> 2
    }

    companion object {
        const val CHANNELS = 4L
    }
}
