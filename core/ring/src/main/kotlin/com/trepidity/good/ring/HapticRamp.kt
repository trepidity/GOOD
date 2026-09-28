package com.trepidity.good.ring

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vibration that grows from one 400 ms pulse every 20 s to three 800 ms pulses every 5 s over [start]'s ramp.
 * Uses full amplitude: the OnePlus Watch 2R's motor is weak, so duration does the work.
 */
class HapticRamp(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var rampMs = 180_000L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val p = min(1f, (SystemClock.elapsedRealtime() - startedAt).toFloat() / rampMs)
            val pulses = 1 + (2 * p).roundToInt()
            val pulseMs = (400 + 400 * p).toLong()
            val gapMs = (20_000 - 15_000 * p).toLong()
            buzz(pulses, pulseMs)
            handler.postDelayed(this, gapMs + pulses * (pulseMs + 250))
        }
    }

    val hasAmplitudeControl: Boolean get() = vibrator.hasAmplitudeControl()

    /** [elapsedMs] > 0 resumes a ramp that should have started that long ago (late start after a reboot). */
    fun start(rampMs: Long, elapsedMs: Long = 0) {
        stop()
        this.rampMs = rampMs.coerceAtLeast(1)
        startedAt = SystemClock.elapsedRealtime() - elapsedMs.coerceAtLeast(0)
        running = true
        handler.post(tick)
    }

    /** Escalation: long pulses on repeat until [stop]. */
    fun continuous() {
        stop()
        running = true
        val timings = longArrayOf(0, 900, 300)
        val amps = intArrayOf(0, amplitude(), 0)
        vibrate(VibrationEffect.createWaveform(timings, amps, 1))
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        vibrator.cancel()
    }

    private fun buzz(pulses: Int, pulseMs: Long) {
        val timings = LongArray(pulses * 2) { i -> if (i % 2 == 0) (if (i == 0) 0L else 250L) else pulseMs }
        val amps = IntArray(pulses * 2) { i -> if (i % 2 == 0) 0 else amplitude() }
        vibrate(VibrationEffect.createWaveform(timings, amps, -1))
    }

    private fun amplitude() = if (vibrator.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE

    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
