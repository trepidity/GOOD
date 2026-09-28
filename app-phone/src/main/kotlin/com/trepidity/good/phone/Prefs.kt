package com.trepidity.good.phone

import android.content.Context
import androidx.core.content.edit

/** Small settings (credential-encrypted). The schedule itself is in the device-protected ScheduleStore. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("good_settings", Context.MODE_PRIVATE)

    var sleepGoalMin: Int
        get() = prefs.getInt("sleepGoalMin", 450)
        set(v) = prefs.edit { putInt("sleepGoalMin", v.coerceIn(240, 720)) }

    var bedtimeReminder: Boolean
        get() = prefs.getBoolean("bedtimeReminder", true)
        set(v) = prefs.edit { putBoolean("bedtimeReminder", v) }

    /** Last "log bedtime" press, for the provisional session when the phone's own button is used. */
    var lastBedtime: Long
        get() = prefs.getLong("lastBedtime", 0)
        set(v) = prefs.edit { putLong("lastBedtime", v) }
}
