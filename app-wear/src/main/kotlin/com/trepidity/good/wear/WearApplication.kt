package com.trepidity.good.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.UserManager
import com.trepidity.good.wear.alarm.WatchScheduleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class WearApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false) // HapticRamp drives the motor
            }
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ARMED, getString(R.string.channel_armed), NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        // Components run before first unlock too (direct boot); the stores are device-protected, but prime them
        // only once the user is unlocked so nothing here ever reaches for credential-encrypted storage.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            WatchScheduleStore.load(this)
            WatchScheduleStore.summary(this)
        }
    }

    companion object {
        const val CHANNEL_ALARM = "alarm"
        const val CHANNEL_ARMED = "armed"
    }
}
