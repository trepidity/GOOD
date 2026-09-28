package com.trepidity.good.phone.wake

import android.app.Notification
import android.app.NotificationManager
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
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.GoodApplication
import com.trepidity.good.phone.MainActivity
import com.trepidity.good.phone.alarm.InstanceEvents
import com.trepidity.good.phone.alarm.ScheduleStore
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.phone.sync.WatchStatus
import com.trepidity.good.ring.HapticRamp
import com.trepidity.good.ring.ToneRamp
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

/** What the ringing screen draws. Published before the full-screen intent fires (REVIEW R3). */
data class RingUi(
    val instanceId: String,
    val label: String,
    val fireAt: Instant,
    val lightStart: Instant?,
    val lightEnd: Instant?,
    val preview: Boolean,
)

/**
 * Runs the phone's share of a wake profile: light ramp (drawn by [RingActivity]), fallback haptics, sound and
 * escalation. A systemExempted foreground service, allowed because GOOD holds USE_EXACT_ALARM. Direct-boot
 * aware: it reads only the device-protected [ScheduleStore], so it rings before the first unlock (REVIEW R1).
 */
class WakeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timeline: Job? = null
    private var entry: ScheduleEntry? = null
    private var preview = false
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
                    Log.i(TAG, "Dismissed on the ${cmd.from}")
                    finish()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_INSTANCE_ID), intent.getBooleanExtra(EXTRA_PREVIEW, false))
            ACTION_DISMISS -> userDismiss()
            else -> if (entry == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(instanceId: String?, preview: Boolean) {
        val e = instanceId?.let { ScheduleStore.find(this, it) }
        if (e == null || e.instance.state.isTerminal) {
            if (entry == null) stopSelf()
            return
        }
        if (entry?.instance?.id == e.instance.id && timeline?.isActive == true) return // backup alarm while ringing
        if (entry != null) finish(stopService = false) // a different occurrence supersedes the current one

        val fireAt = Instant.ofEpochMilli(e.instance.scheduledAtEpochMs)
        val factor = if (preview) PREVIEW_FACTOR else 1.0
        val silenceAt = fireAt.plusMillis((Duration.ofMinutes(e.profile.autoSilenceMinutes.toLong()).toMillis() * factor).toLong())
        val now = Instant.now()

        // Started so late that the whole occurrence is over (e.g. phone was off): log it, don't ring (REVIEW R7).
        if (!now.isBefore(silenceAt)) {
            EventLog.log(this, "MISSED", "${e.instance.id} started after its silence time")
            InstanceEvents.record(this, e.instance.copy(state = InstanceState.SILENCED, currentStage = null))
            if (entry == null) stopSelf()
            return
        }

        entry = e
        this.preview = preview
        // Worst-case plan (watch missing) gives the light-stage times; publish before the full-screen intent.
        val provisional = plan(e, fireAt, factor, watchAvailable = false)
        ui.value = ringUi(e, fireAt, provisional, preview)
        goForeground(e)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "good:wake")
            .apply { acquire(WakePlanner.holdMs(now, fireAt, e.profile).coerceAtMost(HARD_CAP_MS)) }
        EventLog.log(this, "RING_START", "${e.instance.id}${if (preview) " preview" else ""}")

        timeline = scope.launch {
            val watch = if (preview) WatchStatus(false, false) else PhoneSync.pingWatch(this@WakeService)
            val plan = plan(e, fireAt, factor, watch.available)
            EventLog.log(this@WakeService, "PLAN", "watch reachable=${watch.reachable} worn=${watch.worn} · ${plan.joinToString { it.type.name }}")
            ui.value = ringUi(e, fireAt, plan, preview)

            for (stage in plan) {
                waitUntil(stage.startAt)
                runStage(stage, e)
            }
            waitUntil(silenceAt)
            autoSilence()
        }
    }

    private fun plan(e: ScheduleEntry, fireAt: Instant, factor: Double, watchAvailable: Boolean): List<PlannedStage> {
        val plan = WakePlanner.plan(fireAt, e.profile, Device.PHONE, e.soundTarget, watchAvailable)
        return if (factor == 1.0) plan else WakePlanner.compress(plan, fireAt, factor)
    }

    private fun ringUi(e: ScheduleEntry, fireAt: Instant, plan: List<PlannedStage>, preview: Boolean): RingUi {
        val light = plan.firstOrNull { it.type == StageType.LIGHT }
        return RingUi(e.instance.id, e.label, fireAt, light?.startAt, light?.let { it.startAt.plusSeconds(it.rampSec.toLong()) }, preview)
    }

    /** A stage whose start already passed (late start) resumes at its current ramp position (REVIEW R7). */
    private fun runStage(stage: PlannedStage, e: ScheduleEntry) {
        val elapsedMs = Duration.between(stage.startAt, Instant.now()).toMillis().coerceAtLeast(0)
        Log.i(TAG, "Stage ${stage.type} (+$elapsedMs ms late)")
        record(WakeEvent.StageStarted(stage.type))
        when (stage.type) {
            StageType.LIGHT -> Unit // RingActivity animates the backlight from RingUi
            StageType.HAPTIC -> haptics.start(stage.rampSec * 1000L, elapsedMs)
            StageType.SOUND -> {
                guardVolume()
                tone.start(
                    startGain = stage.params[StageParams.START_GAIN]?.toFloatOrNull() ?: 0.05f,
                    rampMs = stage.rampSec * 1000L,
                    stepMs = if (preview) 1_000 else 10_000,
                    tone = e.tone,
                    elapsedMs = elapsedMs,
                )
            }
            StageType.ESCALATE -> {
                guardVolume()
                tone.max(e.tone)
                haptics.continuous()
            }
        }
    }

    private fun guardVolume() {
        if (tone.ensureAudible()) EventLog.log(this, "VOLUME_RAISED", "alarm stream was 0; set to half")
    }

    private fun userDismiss() {
        val e = entry ?: return finish()
        val now = Instant.now()
        val next = WakeStateMachine.reduce(e.instance, WakeEvent.Dismiss(Device.PHONE), now, e.profile)
        if (!preview) {
            InstanceEvents.record(this, next)
            val cmd = Command(next.id, now.toEpochMilli(), Device.PHONE)
            (application as GoodApplication).appScope.launch {
                val reached = runCatching { PhoneSync.sendDismiss(this@WakeService, next, cmd) }.getOrDefault(0)
                EventLog.log(this@WakeService, "DISMISS", "${next.id} at stage ${e.instance.currentStage} · watch nodes reached: $reached")
            }
        } else {
            ScheduleStore.update(this, next)
        }
        finish()
    }

    private fun autoSilence() {
        val e = entry ?: return finish()
        Log.i(TAG, "Auto-silenced")
        val next = WakeStateMachine.reduce(e.instance, WakeEvent.AutoSilence, Instant.now(), e.profile)
        if (!preview) {
            InstanceEvents.record(this, next)
            EventLog.log(this, "UNANSWERED", next.id)
            notifyUnanswered(e)
        }
        finish()
    }

    private fun record(event: WakeEvent) {
        val e = entry ?: return
        val next = WakeStateMachine.reduce(e.instance, event, Instant.now(), e.profile)
        entry = e.copy(instance = next)
        if (preview) ScheduleStore.update(this, next) else InstanceEvents.record(this, next)
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

    private fun goForeground(e: ScheduleEntry) {
        val fullScreen = PendingIntent.getActivity(
            this, 1, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // No notification actions on purpose: there is no snooze, and dismiss is the 2-second hold on the ringing screen.
        val notification: Notification = NotificationCompat.Builder(this, GoodApplication.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(e.label.ifBlank { "GOOD alarm" })
            .setContentText("Waking you gently · hold STOP to dismiss")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
    }

    private fun notifyUnanswered(e: ScheduleEntry) {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, GoodApplication.CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm went unanswered")
            .setContentText("${e.label.ifBlank { "GOOD alarm" }} rang for ${e.profile.autoSilenceMinutes} min and was silenced.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(UNANSWERED_ID, n) }
    }

    companion object {
        private const val TAG = "GoodWakeService"
        private const val NOTIFICATION_ID = 42
        private const val UNANSWERED_ID = 44
        /** Longest plan GOOD allows (30-min lead + 30-min silence) plus margin. */
        private const val HARD_CAP_MS = 65 * 60_000L
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
