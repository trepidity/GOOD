package com.trepidity.good.phone.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Credential-encrypted: only usable after the first unlock. Everything the ring path needs before that
 * lives in the device-protected [com.trepidity.good.phone.alarm.ScheduleStore] (REVIEW R1).
 */
@Database(
    entities = [
        AlarmEntity::class, ProfileEntity::class, InstanceEntity::class, SleepSessionEntity::class,
        SleepSegmentEntity::class, SleepSignalEntity::class, AnchorEntity::class, EventLogEntity::class,
    ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class GoodDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao
    abstract fun profiles(): ProfileDao
    abstract fun instances(): InstanceDao
    abstract fun sleep(): SleepDao
    abstract fun events(): EventLogDao

    companion object {
        fun build(context: Context): GoodDatabase =
            Room.databaseBuilder(context, GoodDatabase::class.java, "good.db").build()
    }
}
