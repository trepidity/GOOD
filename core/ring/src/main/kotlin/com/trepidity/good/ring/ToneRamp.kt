package com.trepidity.good.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A soft, looping two-note chime on the alarm stream whose gain rises in steps until stopped.
 * Generated in code so the app ships no audio assets; swap for a MediaPlayer + ringtone later.
 *
 * The final loudness is gain × the user's alarm-volume setting.
 */
class ToneRamp(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var startedAt = 0L
    private var startGain = 0.05f
    private var endGain = 1f
    private var rampMs = 300_000L
    private var stepMs = 10_000L

    private val stepper = object : Runnable {
        override fun run() {
            val t = track ?: return
            val progress = min(1f, (SystemClock.elapsedRealtime() - startedAt).toFloat() / rampMs)
            t.setVolume(startGain + (endGain - startGain) * progress)
            if (progress < 1f) handler.postDelayed(this, stepMs)
        }
    }

    val isPlaying: Boolean get() = track != null

    /** Start (or restart) the ramp. [preferSpeaker] routes to the built-in speaker, e.g. on the watch. */
    fun start(
        startGain: Float = 0.05f,
        endGain: Float = 1f,
        rampMs: Long = 300_000L,
        stepMs: Long = 10_000L,
        preferSpeaker: Boolean = false,
    ) {
        stop()
        this.startGain = startGain
        this.endGain = endGain
        this.rampMs = rampMs.coerceAtLeast(1)
        this.stepMs = stepMs.coerceAtLeast(250)

        val pcm = chime()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        if (preferSpeaker) builtInSpeaker()?.let { t.setPreferredDevice(it) }
        t.setVolume(startGain)
        t.play()
        track = t
        startedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(stepper, this.stepMs)
    }

    /** Jump straight to full gain (escalation). */
    fun max() {
        if (track == null) start(startGain = 1f, rampMs = 1)
        startGain = 1f; endGain = 1f
        track?.setVolume(1f)
    }

    fun stop() {
        handler.removeCallbacks(stepper)
        track?.run {
            runCatching { stop() }
            release()
        }
        track = null
    }

    /** Lists output devices, for the M0 spike screen. */
    fun describeOutputs(): List<String> =
        audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { "${it.type}:${it.productName}" }

    private fun builtInSpeaker(): AudioDeviceInfo? =
        audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun audioManager() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 2 s loop: two decaying sine notes (A5, E6) then silence. */
    private fun chime(): ShortArray {
        val total = SAMPLE_RATE * 2
        val out = ShortArray(total)
        fun note(freq: Double, startSec: Double, lenSec: Double) {
            val s0 = (startSec * SAMPLE_RATE).toInt()
            val n = (lenSec * SAMPLE_RATE).toInt()
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= total) break
                val tSec = i.toDouble() / SAMPLE_RATE
                val env = exp(-4.0 * tSec) * min(1.0, tSec / 0.01)
                val v = 0.6 * env * sin(2 * PI * freq * tSec)
                out[idx] = (out[idx] + (v * Short.MAX_VALUE).toInt()).coerceIn(-32767, 32767).toShort()
            }
        }
        note(880.0, 0.0, 0.9)
        note(1318.5, 0.35, 0.9)
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
