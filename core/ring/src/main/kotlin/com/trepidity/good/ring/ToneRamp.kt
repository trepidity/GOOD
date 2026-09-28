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
import com.trepidity.good.model.Tone
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A looping tone on the alarm stream whose gain rises in steps until stopped: a soft two-note chime,
 * or the four-beep digital-watch alarm. Generated in code, so the app ships no audio assets.
 *
 * The final loudness is gain × the user's alarm-volume setting; see [ensureAudible].
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
        tone: Tone = Tone.CHIME,
        elapsedMs: Long = 0,
    ) {
        stop()
        this.startGain = startGain
        this.endGain = endGain
        this.rampMs = rampMs.coerceAtLeast(1)
        this.stepMs = stepMs.coerceAtLeast(250)

        val pcm = if (tone == Tone.CLASSIC) classic() else chime()
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
        startedAt = SystemClock.elapsedRealtime() - elapsedMs.coerceAtLeast(0)
        val progress = min(1f, (SystemClock.elapsedRealtime() - startedAt).toFloat() / this.rampMs)
        t.setVolume(startGain + (endGain - startGain) * progress)
        t.play()
        track = t
        if (progress < 1f) handler.postDelayed(stepper, this.stepMs)
    }

    /** Jump straight to full gain (escalation). */
    fun max(tone: Tone = Tone.CHIME, preferSpeaker: Boolean = false) {
        if (track == null) start(startGain = 1f, rampMs = 1, tone = tone, preferSpeaker = preferSpeaker)
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

    /**
     * A zero alarm-stream volume would make every stage silent (REVIEW R6). Raises it to half and returns
     * true when it had to; false when it was already audible or the system refused.
     */
    fun ensureAudible(): Boolean {
        val am = audioManager()
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) > 0) return false
        return runCatching {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM) / 2, 0)
        }.isSuccess
    }

    val alarmVolumeIsZero: Boolean get() = audioManager().getStreamVolume(AudioManager.STREAM_ALARM) == 0

    /** Lists output devices, for the CHK screen. */
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

    /** 1 s loop: four 70 ms beeps at 2 kHz, then a pause. Softened square wave, so it's piercing but not harsh. */
    private fun classic(): ShortArray {
        val out = ShortArray(SAMPLE_RATE)
        val beep = (0.07 * SAMPLE_RATE).toInt()
        val gap = (0.07 * SAMPLE_RATE).toInt()
        for (b in 0 until 4) {
            val s0 = b * (beep + gap)
            for (i in 0 until beep) {
                val tSec = i.toDouble() / SAMPLE_RATE
                val edge = min(1.0, min(i, beep - i).toDouble() / (0.004 * SAMPLE_RATE))
                val sq = sin(2 * PI * 2048.0 * tSec) + sin(2 * PI * 3 * 2048.0 * tSec) / 3
                out[s0 + i] = (0.45 * edge * sq * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
