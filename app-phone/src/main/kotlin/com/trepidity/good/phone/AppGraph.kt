package com.trepidity.good.phone

import android.content.Context
import android.os.UserManager
import com.trepidity.good.phone.alarm.AlarmRepository
import com.trepidity.good.phone.data.GoodDatabase
import com.trepidity.good.phone.sleep.SleepRepository

/**
 * The app's singletons, built on first use (a hand-written stand-in for Hilt; see SPEC Decisions).
 * Everything here touches credential-encrypted storage, so callers on the ring path check [isUnlocked] first.
 */
object AppGraph {
    @Volatile private var db: GoodDatabase? = null
    @Volatile private var alarms: AlarmRepository? = null
    @Volatile private var sleep: SleepRepository? = null

    fun isUnlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java).isUserUnlocked

    fun db(context: Context): GoodDatabase = db ?: synchronized(this) {
        db ?: GoodDatabase.build(context.applicationContext).also { db = it }
    }

    fun alarms(context: Context): AlarmRepository = alarms ?: synchronized(this) {
        alarms ?: AlarmRepository(context.applicationContext, db(context)).also { alarms = it }
    }

    fun sleep(context: Context): SleepRepository = sleep ?: synchronized(this) {
        sleep ?: SleepRepository(context.applicationContext, db(context)).also { sleep = it }
    }

    fun prefs(context: Context) = Prefs(context.applicationContext)
}
