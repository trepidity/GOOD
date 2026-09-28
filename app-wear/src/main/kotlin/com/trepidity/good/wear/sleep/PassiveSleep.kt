package com.trepidity.good.wear.sleep

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import com.trepidity.good.model.SleepSignalMessage
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.sync.WatchSync
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Sleep source 2: Health Services passive asleep/awake state, used only if this watch reports the capability
 * (an M0 question for the 2R). Passive monitoring batches in the OS, so it costs no continuous listener.
 */
object PassiveSleep {
    private const val TAG = "GoodPassiveSleep"

    fun register(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        (context.applicationContext as WearApplication).appScope.launch {
            runCatching {
                val client = HealthServices.getClient(context).passiveMonitoringClient
                val caps = client.getCapabilitiesAsync().await()
                if (UserActivityState.USER_ACTIVITY_ASLEEP !in caps.supportedUserActivityStates) {
                    Log.i(TAG, "Asleep state not supported on this watch")
                    return@launch
                }
                client.setPassiveListenerServiceAsync(
                    PassiveSleepService::class.java,
                    PassiveListenerConfig.Builder().setShouldUserActivityInfoBeRequested(true).build(),
                ).await()
                Log.i(TAG, "Passive sleep state registered")
            }.onFailure { Log.w(TAG, "Passive registration failed", it) }
        }
    }
}

/** Forwards asleep ↔ awake transitions to the phone through the outbox. */
class PassiveSleepService : PassiveListenerService() {
    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val asleep = info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP
        val prefs = createDeviceProtectedStorageContext().getSharedPreferences("good_passive", MODE_PRIVATE)
        if (prefs.contains("asleep") && prefs.getBoolean("asleep", false) == asleep) return
        prefs.edit { putBoolean("asleep", asleep) }
        val msg = SleepSignalMessage(info.stateChangeTime.toEpochMilli(), asleep)
        (application as WearApplication).appScope.launch {
            WatchSync.send(this@PassiveSleepService, DataLayerPaths.SLEEP_SIGNAL, SyncCodec.encodeAny(msg))
        }
    }
}
