package com.trepidity.good.wear.spike

import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.health.services.client.HealthServices
import com.trepidity.good.ring.HapticRamp
import com.trepidity.good.ring.ToneRamp
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import kotlinx.coroutines.guava.await

/** M0 answers for the OnePlus Watch 2R: can GOOD ring, buzz, play sound and sense sleep here? */
object WatchProbe {

    fun basics(context: Context): List<String> {
        val sensors = context.getSystemService(SensorManager::class.java)
        val offBody = sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true) != null
        return listOf(
            "Exact alarms: ${WatchAlarmScheduler.canScheduleExact(context)}",
            "Full-screen: ${context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()}",
            "Amplitude control: ${HapticRamp(context).hasAmplitudeControl}",
            "Off-body sensor: $offBody",
            "Audio out: ${ToneRamp(context).describeOutputs().joinToString()}",
        )
    }

    /** Does Health Services on this watch report asleep/awake, and which passive data types? */
    suspend fun healthServices(context: Context): List<String> = runCatching {
        val caps = HealthServices.getClient(context).passiveMonitoringClient.getCapabilitiesAsync().await()
        listOf(
            "User states: ${caps.supportedUserActivityStates.joinToString { it.name }}",
            "Passive types: ${caps.supportedDataTypesPassiveMonitoring.joinToString { it.name }}",
        )
    }.getOrElse { listOf("Health Services error: ${it.message}") }
}
