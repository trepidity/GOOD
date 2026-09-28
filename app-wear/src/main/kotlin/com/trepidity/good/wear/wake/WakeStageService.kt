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
import com.trepidity.good.model.ScheduleEntry
import com.trepidity.good.model.StageParams
import com.trepidity.good.model.StageType
import com.trepidity.good.ring.HapticRamp
import com.trepidity.good.ring.ToneRamp
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.wake.PlannedStage
import com.trepidity.good.wake.WakeEvent
import com.trepidity.good.wake.WakePlanner
import com.trepidity.good.wake.WakeStateMachine
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import com.trepidity.good.wear.alarm.WatchScheduleStore
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

/** What the watch ringing screen needs: when to show the dismiss swipe. */
data class WatchRingUi(val instanceId: String, val controlsAt: Instant)

/**
 * The watch's share of the wake profile: haptic ramp from T−3, then (by default) the sound,
 * starting soft on the built-in speaker and rising until dismissed. No snooze, by design.
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
            ACTION_TEST_HAPTICS -> { goForeground("Haptic test"); haptics.start(30_000); stopLater(40_000) }
            ACTION_TEST_SOUND -> { goForeground("Sound test"); tone.start(startGain = 0.05f, rampMs = 30_000, stepMs = 2_000, preferSpeaker = true); stopLater(40_000) }
            ACTION_STOP_TEST -> finish()
        }
        return START_NOT_STICKY
    }

    private fun start(instanceId: String?) {
        val e = instanceId?.let { WatchScheduleStore.find(this, it) }
        if (e == null || e.instance.state.isTerminal) {
            if (entry == null) stopSelf()
            return
        }
        if (entry?.instance?.id == e.instance.id && timeline?.isActive == true) return
        entry = e
        goForeground(e.label.ifBlank { "GOOD alarm" })
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "good:watch-wake")
            .apply { acquire(25 * 60_000L) }

        timeline?.cancel()
        timeline = scope.launch {
            val fireAt = Instant.ofEpochMilli(e.instance.scheduledAtEpochMs)
            // M2: watchAvailable = off-body sensor says worn. Assumed worn for M0.
            val plan = WakePlanner.plan(fireAt, e.profile, Device.WATCH, e.soundTarget, watchAvailable = true)
            Log.i(TAG, "Plan: $plan")
            val controlsAt = plan.firstOrNull { it.type == StageType.SOUND || it.type == StageType.ESCALATE }?.startAt ?: fireAt
            ui.value = WatchRingUi(e.instance.id, controlsAt)
            for (stage in plan) {
                waitUntil(stage.startAt)
                run(stage)
            }
            waitUntil(WakePlanner.silenceAt(fireAt, e.profile))
            record(WakeEvent.AutoSilence)
            finish()
        }
    }

    private fun run(stage: PlannedStage) {
        Log.i(TAG, "Stage ${stage.type}")
        record(WakeEvent.StageStarted(stage.type))
        when (stage.type) {
            StageType.LIGHT -> Unit
            StageType.HAPTIC -> haptics.start(stage.rampSec * 1000L)
            StageType.SOUND -> tone.start(
                startGain = stage.params[StageParams.START_GAIN]?.toFloatOrNull() ?: 0.05f,
                rampMs = stage.rampSec * 1000L,
                preferSpeaker = true,
            )
            StageType.ESCALATE -> { tone.max(); haptics.continuous() }
        }
    }

    private fun dismiss() {
        val e = entry ?: return finish()
        val now = Instant.now()
        val next = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.WATCH), now, e.profile)
        WatchScheduleStore.update(this, next)
        WatchAlarmScheduler.cancel(this, next.id)
        (application as WearApplication).appScope.launch {
            val sent = runCatching { WatchSync.sendToPhone(this@WakeStageService, DataLayerPaths.CMD_DISMISS, Command(next.id, now.toEpochMilli(), Device.WATCH)) }
            Log.i(TAG, "Dismiss sent to phone: ${sent.getOrNull()}")
        }
        finish()
    }

    private fun record(event: WakeEvent) {
        val e = entry ?: return
        val next = WakeStateMachine.reduce(e.instance, event, Instant.now(), e.profile)
        entry = e.copy(instance = next)
        WatchScheduleStore.update(this, next)
    }

    private fun stopLater(ms: Long) {
        timeline?.cancel()
        timeline = scope.launch { delay(ms); finish() }
    }

    private fun finish() {
        timeline?.cancel()
        tone.stop()
        haptics.stop()
        ui.value = null
        wakeLock?.takeIf { it.isHeld }?.release()
        entry = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
