package com.trepidity.good.wear.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.wear.alarm.WatchAlarmScheduler
import com.trepidity.good.wear.sync.WatchSync
import com.trepidity.good.wear.wake.WornSensor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** CHK items, in ▲▼ order. [code] is what the LCD shows, [speech] what TalkBack reads. */
enum class CheckItem(val code: String, val speech: String) {
    EXA("EXA", "Exact alarms"),
    FSI("FSI", "Full-screen alarm"),
    NTF("NTF", "Notifications"),
    BODY("BODY", "Wrist sensor"),
    LINK("LINK", "Phone link"),
    ACT("ACT", "Activity recognition, for sleep"),
    BUZ("BUZ", "Haptic test"),
    SND("SND", "Sound test"),
}

/** [ok] is null for items that are tests rather than checks. [detail] is the short LCD line. */
data class CheckResult(val ok: Boolean?, val detail: String, val speech: String)

/** The watch's self-test: can GOOD ring, buzz, reach the phone and sense sleep here? */
object WatchChecks {
    suspend fun run(context: Context): Map<CheckItem, CheckResult> = coroutineScope {
        val worn = async { WornSensor.isWorn(context) }
        val reachable = async { phoneReachable(context) }
        val offBody = context.getSystemService(SensorManager::class.java)
            .getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true) != null
        val queued = WatchSync.queuedCount(context)
        val fix = "HOLD FIX"
        fun perm(granted: Boolean) = CheckResult(granted, if (granted) "" else fix, if (granted) "OK" else "Not allowed. Long-press to fix")
        val isWorn = worn.await()
        val linked = reachable.await()
        mapOf(
            CheckItem.EXA to perm(WatchAlarmScheduler.canScheduleExact(context)),
            CheckItem.FSI to perm(canFullScreen(context)),
            CheckItem.NTF to perm(granted(context, Manifest.permission.POST_NOTIFICATIONS)),
            CheckItem.BODY to when {
                !offBody -> CheckResult(false, "NO SENSOR", "No off-body sensor")
                !isWorn -> CheckResult(false, "OFF WRIST", "Not on the wrist")
                else -> CheckResult(true, "WORN", "On the wrist")
            },
            CheckItem.LINK to CheckResult(
                linked, "OUT $queued",
                (if (linked) "Phone reachable" else "Phone not reachable") + ", $queued queued. Long-press to retry",
            ),
            CheckItem.ACT to perm(granted(context, Manifest.permission.ACTIVITY_RECOGNITION)),
            CheckItem.BUZ to CheckResult(null, "HOLD TEST", "Long-press to test the vibration ramp"),
            CheckItem.SND to CheckResult(null, "HOLD TEST", "Long-press to test the sound ramp"),
        )
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canFullScreen(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private suspend fun phoneReachable(context: Context): Boolean = withTimeoutOrNull(3_000) {
        runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(DataLayerPaths.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await().nodes.isNotEmpty()
        }.getOrDefault(false)
    } ?: false
}
