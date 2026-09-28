package com.trepidity.good.wear.wake

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trepidity.good.model.Command
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.StageParams
import com.trepidity.good.model.StageType
import com.trepidity.good.ring.HapticRamp
import com.trepidity.good.ring.ToneRamp
import com.trepidity.good.wake.PlannedStage
import com.trepidity.good.wake.WakeEvent
import com.trepidity.good.wake.WakePlanner
import com.trepidity.good.wake.WakeStateMachine
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.surface.WatchSurfaces
import com.trepidity.good.wear.sync.WatchSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** What the watch ringing screen needs. Hold-to-stop is armed from the first stage (REVIEW U1). */
data class WatchRingUi(val instanceId: String, val label: String, val fireAt: Instant)

/**
 * The watch's share of the wake profile: haptic ramp from T−3, then (by default) the sound, starting soft on the
 * built-in speaker and rising until dismissed. Runs from the device-protected snapshot, with the phone out of
 * range and before first unlock. No snooze, by design.
 */
class WakeStageService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeline: Job? = null
    private var entry: ScheduleEntry? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var tone: ToneRamp
    private lateinit var haptics: HapticRamp

    override fun onCreate() {
        super.onCreate()
        tone = ToneRamp(this)
        haptics = HapticRamp(this)
        scope.launch {
            remoteCommands.collect { if (it.instanceId == entry?.instance?.id) finish() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_INSTANCE_ID))
            ACTION_DISMISS -> dismiss()
            ACTION_TEST_HAPTICS -> test("Haptic test") { haptics.start(30_000) }
            ACTION_TEST_SOUND -> test("Sound test") { tone.start(startGain = 0.05f, rampMs = 30_000, stepMs = 2_000, preferSpeaker = true) }
            ACTION_STOP_TEST -> if (entry == null) finish()
            else -> if (entry == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(instanceId: String?) {
        val e = instanceId?.let { WatchScheduleStore.find(this, it) }
        if (e == null || e.instance.state.isTerminal) {
            if (entry == null) stopSelf()
            return
        }
        if (entry?.instance?.id == e.instance.id && timeline?.isActive == true) return // backup alarm while ringing
        if (entry != null) finish(stopService = false)

        val fireAt = Instant.ofEpochMilli(e.instance.scheduledAtEpochMs)
        val silenceAt = WakePlanner.silenceAt(fireAt, e.profile)
        val now = Instant.now()
        if (!now.isBefore(silenceAt)) {
            record(e, WakeEvent.AutoSilence)
            if (entry == null) stopSelf()
            return
        }

        entry = e
        ui.value = WatchRingUi(e.instance.id, e.label, fireAt) // before the full-screen intent (REVIEW R3)
        goForeground(e.label.ifBlank { "GOOD alarm" })
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "good:watch-wake")
            .apply { acquire(WakePlanner.holdMs(now, fireAt, e.profile).coerceAtMost(HARD_CAP_MS)) }

        timeline = scope.launch {
            // Off the wrist, the watch leaves the sound to the phone (the phone learned the same from its /health ping).
            val worn = WornSensor.isWorn(this@WakeStageService)
            val plan = WakePlanner.plan(fireAt, e.profile, Device.WATCH, e.soundTarget, watchAvailable = worn)
            Log.i(TAG, "Plan (worn=$worn): $plan")
            for (stage in plan) {
                waitUntil(stage.startAt)
                run(stage, e)
            }
            waitUntil(silenceAt)
            entry?.let { record(it, WakeEvent.AutoSilence) }
            finish()
        }
    }

    /** A stage whose start already passed resumes at its current ramp position (REVIEW R7). */
    private fun run(stage: PlannedStage, e: ScheduleEntry) {
        val elapsedMs = Duration.between(stage.startAt, Instant.now()).toMillis().coerceAtLeast(0)
        Log.i(TAG, "Stage ${stage.type} (+$elapsedMs ms late)")
        entry?.let { record(it, WakeEvent.StageStarted(stage.type)) }
        when (stage.type) {
            StageType.LIGHT -> Unit
            StageType.HAPTIC -> haptics.start(stage.rampSec * 1000L, elapsedMs)
            StageType.SOUND -> {
                tone.ensureAudible()
                tone.start(
                    startGain = stage.params[StageParams.START_GAIN]?.toFloatOrNull() ?: 0.05f,
                    rampMs = stage.rampSec * 1000L,
                    preferSpeaker = true,
                    tone = e.tone,
                    elapsedMs = elapsedMs,
                )
            }
            StageType.ESCALATE -> {
                tone.ensureAudible()
                tone.max(e.tone, preferSpeaker = true)
                haptics.continuous()
            }
        }
    }

    private fun dismiss() {
        val e = entry ?: return finish()
        val now = Instant.now()
        val next = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.WATCH), now, e.profile)
        WatchScheduleStore.update(this, next)
        WatchAlarmScheduler.cancelChannel(this, next.alarmId)
        (application as WearApplication).appScope.launch {
            val sent = runCatching { WatchSync.sendDismiss(this@WakeStageService, next, Command(next.id, now.toEpochMilli(), Device.WATCH)) }
            Log.i(TAG, "Dismiss delivered to phone now: ${sent.getOrNull()}")
        }
        WatchSurfaces.refresh(this)
        finish()
    }

    private fun record(e: ScheduleEntry, event: WakeEvent) {
        val next = WakeStateMachine.reduce(e.instance, event, Instant.now(), e.profile)
        if (entry?.instance?.id == e.instance.id) entry = e.copy(instance = next)
        WatchScheduleStore.update(this, next)
        if (next.state == InstanceState.SILENCED) WatchSurfaces.refresh(this)
    }

    private fun test(title: String, block: () -> Unit) {
        if (entry != null) return // never interrupt a real alarm with a test
        goForeground(title)
        block()
        timeline?.cancel()
        timeline = scope.launch { delay(40_000); finish() }
    }

    private fun finish(stopService: Boolean = true) {
        timeline?.cancel()
        timeline = null
        tone.stop()
        haptics.stop()
        ui.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        entry = null
        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        tone.stop()
        haptics.stop()
        ui.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun waitUntil(at: Instant) {
        val ms = Duration.between(Instant.now(), at).toMillis()
        if (ms > 0) delay(ms)
    }

    private fun goForeground(title: String) {
        val ring = PendingIntent.getActivity(
            this, 1, Intent(this, WatchRingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, WearApplication.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("Hold anywhere 2 s to stop")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(ring, true)
            .setContentIntent(ring)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
    }

    companion object {
        private const val TAG = "GoodWakeStage"
        private const val NOTIFICATION_ID = 43
        private const val HARD_CAP_MS = 65 * 60_000L

        const val ACTION_START = "com.trepidity.good.wear.START"
        const val ACTION_DISMISS = "com.trepidity.good.wear.DISMISS"
        const val ACTION_TEST_HAPTICS = "com.trepidity.good.wear.TEST_HAPTICS"
        const val ACTION_TEST_SOUND = "com.trepidity.good.wear.TEST_SOUND"
        const val ACTION_STOP_TEST = "com.trepidity.good.wear.STOP_TEST"
        const val EXTRA_INSTANCE_ID = "instanceId"

        val ui = MutableStateFlow<WatchRingUi?>(null)
        val remoteCommands = MutableSharedFlow<Command>(extraBufferCapacity = 4)

        fun startIntent(context: Context, instanceId: String): Intent =
            Intent(context, WakeStageService::class.java).setAction(ACTION_START).putExtra(EXTRA_INSTANCE_ID, instanceId)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, WakeStageService::class.java).setAction(action)
    }
}
