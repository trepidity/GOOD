package com.trepidity.good.wear.wake

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Is the watch on a wrist? The low-latency off-body sensor reports its current state as soon as a listener
 * registers, so a short one-shot read costs almost nothing. No sensor, or no answer, counts as worn: the phone
 * still joins as the safety net, so erring toward "worn" never silences an alarm.
 */
object WornSensor {
    suspend fun isWorn(context: Context, timeoutMs: Long = 2_000): Boolean {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true) ?: return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull() == 1f)
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        } ?: true
    }
}
