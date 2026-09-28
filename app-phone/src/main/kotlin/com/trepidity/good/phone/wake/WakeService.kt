package com.trepidity.good.phone.wake

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import com.trepidity.good.phone.GoodApplication
import com.trepidity.good.phone.alarm.AlarmScheduler
import com.trepidity.good.phone.alarm.ScheduleStore
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.ring.HapticRamp
import com.trepidity.good.ring.ToneRamp
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.wake.PlannedStage
import com.trepidity.good.wake.WakeEvent
import com.trepidity.good.wake.WakePlanner
import com.trepidity.good.wake.WakeStateMachine
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

/** What the ringing screen needs to draw the light ramp and decide when to show controls. */
data class RingUi(
    val instanceId: String,
    val label: String,
    val lightStart: Instant?,
    val lightEnd: Instant?,
    val controlsAt: Instant,
)

/**
 * Runs the phone's share of a wake profile: light ramp (via [RingActivity]), fallback haptics,
 * sound and escalation. A systemExempted foreground service, allowed because GOOD holds USE_EXACT_ALARM.
 */
class WakeService : Service() {
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
            remoteCommands.collect { cmd ->
                if (cmd.instanceId == entry?.instance?.id) {
                    Log.i(TAG, "Dismissed on the watch")
                    finish()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_INSTANCE_ID)
        when (intent?.action) {
            ACTION_START -> start(id, preview = intent.getBooleanExtra(EXTRA_PREVIEW, false))
            ACTION_DISMISS -> userDismiss()
        }
        return START_NOT_STICKY
    }

    private fun start(instanceId: String?, preview: Boolean) {
        val e = instanceId?.let { ScheduleStore.find(this, it) }
        if (e == null || e.instance.state.isTerminal) {
            if (entry == null) stopSelf()
            return
        }
        if (entry?.instance?.id == e.instance.id && timeline?.isActive == true) return // backup alarm, already ringing
        entry = e
        goForeground(e)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "good:wake")
            .apply { acquire(25 * 60_000L) }

        timeline?.cancel()
        timeline = scope.launch {
            val fireAt = Instant.ofEpochMilli(e.instance.scheduledAtEpochMs)
            val watchAvailable = !preview && PhoneSync.isWatchReachable(this@WakeService)
            val factor = if (preview) PREVIEW_FACTOR else 1.0
            var plan = WakePlanner.plan(fireAt, e.profile, Device.PHONE, e.soundTarget, watchAvailable)
            if (preview) plan = WakePlanner.compress(plan, fireAt, factor)
            Log.i(TAG, "Plan (watch=$watchAvailable): $plan")

            val light = plan.firstOrNull { it.type == StageType.LIGHT }
            val controlsAt = plan.firstOrNull { it.type == StageType.SOUND || it.type == StageType.ESCALATE }?.startAt ?: fireAt
            ui.value = RingUi(
                instanceId = e.instance.id,
                label = e.label,
                lightStart = light?.startAt,
                lightEnd = light?.let { it.startAt.plusSeconds(it.rampSec.toLong()) },
                controlsAt = minOf(controlsAt, fireAt),
            )

            // Stages whose time has passed (late start after reboot) run immediately, in order.
            for (stage in plan) {
                waitUntil(stage.startAt)
                runStage(stage, preview)
            }
            val silenceAt = fireAt.plusMillis((Duration.ofMinutes(e.profile.autoSilenceMinutes.toLong()).toMillis() * factor).toLong())
            waitUntil(silenceAt)
            Log.i(TAG, "Auto-silenced")
            record(WakeEvent.AutoSilence)
            finish()
        }
    }

    private fun runStage(stage: PlannedStage, preview: Boolean) {
        Log.i(TAG, "Stage ${stage.type} at ${Instant.now()}")
        record(WakeEvent.StageStarted(stage.type))
        when (stage.type) {
            StageType.LIGHT -> Unit // RingActivity animates brightness from RingUi
            StageType.HAPTIC -> haptics.start(stage.rampSec * 1000L)
            StageType.SOUND -> tone.start(
                startGain = stage.params[StageParams.START_GAIN]?.toFloatOrNull() ?: 0.05f,
                rampMs = stage.rampSec * 1000L,
                stepMs = if (preview) 1_000 else 10_000,
            )
            StageType.ESCALATE -> {
                tone.max()
                haptics.continuous()
            }
        }
    }

    private fun userDismiss() {
        val e = entry ?: return finish()
        val now = Instant.now()
        val next = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.PHONE), now, e.profile)
        ScheduleStore.update(this, next)
        AlarmScheduler.cancel(this, next.id)
        tellWatch(DataLayerPaths.CMD_DISMISS, Command(next.id, now.toEpochMilli(), Device.PHONE))
        finish()
    }

    private fun record(event: WakeEvent) {
        val e = entry ?: return
        val next = WakeStateMachine.reduce(e.instance, event, Instant.now(), e.profile)
        entry = e.copy(instance = next)
        ScheduleStore.update(this, next)
    }

    private fun tellWatch(path: String, command: Command) {
        (application as GoodApplication).appScope.launch {
            runCatching { PhoneSync.sendCommand(this@WakeService, path, command) }
                .onFailure { Log.w(TAG, "Could not reach watch", it) }
        }
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

    private fun goForeground(e: ScheduleEntry) {
        val fullScreen = PendingIntent.getActivity(
            this, 1, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // No notification actions on purpose: there is no snooze, and dismiss needs the deliberate slide on the ringing screen.
        val notification: Notification = NotificationCompat.Builder(this, GoodApplication.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(e.label.ifBlank { "GOOD alarm" })
            .setContentText("Waking you gently")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "GoodWakeService"
        private const val NOTIFICATION_ID = 42
        /** Preview squeezes a 10-min light ramp into 30 s. */
        private const val PREVIEW_FACTOR = 0.05

        const val ACTION_START = "com.trepidity.good.action.START"
        const val ACTION_DISMISS = "com.trepidity.good.action.DISMISS"
        const val EXTRA_INSTANCE_ID = "instanceId"
        const val EXTRA_PREVIEW = "preview"

        /** Observed by RingActivity; null when nothing is ringing. */
        val ui = MutableStateFlow<RingUi?>(null)

        /** Dismiss arriving from the watch while ringing. */
        val remoteCommands = MutableSharedFlow<Command>(extraBufferCapacity = 4)

        fun startIntent(context: Context, instanceId: String, preview: Boolean = false): Intent =
            Intent(context, WakeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_PREVIEW, preview)

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, WakeService::class.java).setAction(action)
    }
}
