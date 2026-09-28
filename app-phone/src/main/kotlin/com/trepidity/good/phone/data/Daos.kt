package com.trepidity.good.phone.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarm ORDER BY id") fun observe(): Flow<List<AlarmEntity>>
    @Query("SELECT * FROM alarm ORDER BY id") suspend fun all(): List<AlarmEntity>
    @Query("SELECT * FROM alarm WHERE id = :id") suspend fun get(id: Long): AlarmEntity?
    @Upsert suspend fun upsert(alarm: AlarmEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfMissing(alarms: List<AlarmEntity>)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM wake_profile ORDER BY rowid") fun observe(): Flow<List<ProfileEntity>>
    @Query("SELECT * FROM wake_profile ORDER BY rowid") suspend fun all(): List<ProfileEntity>
    @Upsert suspend fun upsert(profile: ProfileEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfMissing(profiles: List<ProfileEntity>)
}

@Dao
interface InstanceDao {
    @Query("SELECT * FROM alarm_instance WHERE id = :id") suspend fun get(id: String): InstanceEntity?
    @Query("SELECT * FROM alarm_instance WHERE alarmId = :alarmId ORDER BY scheduledAt DESC LIMIT 1")
    suspend fun latest(alarmId: Long): InstanceEntity?
    @Query("SELECT * FROM alarm_instance WHERE scheduledAt >= :from ORDER BY scheduledAt")
    suspend fun since(from: Long): List<InstanceEntity>
    @Query("SELECT * FROM alarm_instance ORDER BY scheduledAt") suspend fun all(): List<InstanceEntity>
    @Upsert suspend fun upsert(instance: InstanceEntity)
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_session WHERE wakeDate = :wakeDate") suspend fun session(wakeDate: String): SleepSessionEntity?
    @Query("SELECT * FROM sleep_session ORDER BY wakeDate DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<SleepSessionEntity>>
    @Query("SELECT * FROM sleep_session ORDER BY wakeDate") suspend fun allSessions(): List<SleepSessionEntity>
    @Query("SELECT * FROM sleep_segment WHERE sessionId = :sessionId ORDER BY start") suspend fun segments(sessionId: Long): List<SleepSegmentEntity>
    @Query("SELECT * FROM sleep_segment ORDER BY start") suspend fun allSegments(): List<SleepSegmentEntity>

    @Insert suspend fun insertSession(session: SleepSessionEntity): Long
    @androidx.room.Update suspend fun updateSession(session: SleepSessionEntity)
    @Insert suspend fun insertSegments(segments: List<SleepSegmentEntity>)
    @Query("DELETE FROM sleep_segment WHERE sessionId = :sessionId") suspend fun deleteSegments(sessionId: Long)

    /** Replace a night's session and its segments in one go. */
    @Transaction
    suspend fun replace(session: SleepSessionEntity, segments: List<SleepSegmentEntity>): Long {
        val id = if (session.id == 0L) insertSession(session) else { updateSession(session); session.id }
        deleteSegments(id)
        insertSegments(segments.map { it.copy(sessionId = id) })
        return id
    }

    @Insert suspend fun insertSignal(signal: SleepSignalEntity)
    @Query("SELECT * FROM sleep_signal WHERE at BETWEEN :from AND :to ORDER BY at") suspend fun signals(from: Long, to: Long): List<SleepSignalEntity>
    @Query("DELETE FROM sleep_signal WHERE at < :before") suspend fun pruneSignals(before: Long)

    @Insert suspend fun insertAnchor(anchor: AnchorEntity)
    @Query("SELECT * FROM sleep_anchor WHERE at BETWEEN :from AND :to ORDER BY at") suspend fun anchors(from: Long, to: Long): List<AnchorEntity>
}

@Dao
interface EventLogDao {
    @Insert suspend fun insert(event: EventLogEntity)
    @Query("SELECT * FROM event_log ORDER BY at DESC LIMIT :limit") suspend fun recent(limit: Int): List<EventLogEntity>
    @Query("DELETE FROM event_log WHERE at < :before") suspend fun prune(before: Long)
}
