package com.trepidity.good.phone

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.trepidity.good.phone.alarm.AlarmScheduler
import com.trepidity.good.phone.sleep.UnlockLog
import com.trepidity.good.phone.sync.PhoneSync

/** The CHK mode's self-test items, in display order (SPEC UX, REVIEW U9). */
enum class CheckId(val code: String, val title: String) {
    ALM("ALM", "Exact alarms"),
    FSI("FSI", "Full-screen ringing"),
    NTF("NTF", "Notifications"),
    BAT("BAT", "Battery unrestricted"),
    VOL("VOL", "Alarm volume"),
    LINK("LINK", "Watch linked"),
    HC("HC", "Health Connect"),
    ACT("ACT", "Sleep signals"),
    USE("USE", "Usage access"),
}

/** One self-test line: whether it's OK, a short detail, and where to fix it (HC's fix is the consent screen). */
data class CheckItem(val id: CheckId, val ok: Boolean, val detail: String, val fix: Intent?)

object ReliabilityCheck {

    suspend fun run(context: Context): List<CheckItem> {
        val pkg = context.packageName
        val pkgUri = Uri.parse("package:$pkg")
        val power = context.getSystemService(PowerManager::class.java)
        val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val unoptimised = power.isIgnoringBatteryOptimizations(pkg)
        val audio = context.getSystemService(AudioManager::class.java)
        val alarmVol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val watch = PhoneSync.pingWatch(context, timeoutMs = 3_000)
        val hc = AppGraph.sleep(context).healthConnect
        val hcOk = hc.available && hc.canRead()

        return listOf(
            CheckItem(CheckId.ALM, AlarmScheduler.canScheduleExact(context), "setAlarmClock allowed",
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)),
            CheckItem(CheckId.FSI, context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(), "rings over the lock screen",
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)),
            CheckItem(CheckId.NTF, NotificationManagerCompat.from(context).areNotificationsEnabled(), "alarm + bedtime notices",
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)),
            CheckItem(CheckId.BAT, unoptimised && !restricted,
                when { restricted -> "background restricted"; !unoptimised -> "set Battery → Unrestricted"; else -> "unrestricted" },
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)),
            CheckItem(CheckId.VOL, alarmVol > 0, "alarm stream $alarmVol/${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}",
                Intent(Settings.ACTION_SOUND_SETTINGS)),
            CheckItem(CheckId.LINK, watch.reachable,
                when { !watch.reachable -> "no watch reachable"; watch.worn -> "reachable, on wrist"; else -> "reachable, off wrist" },
                null),
            CheckItem(CheckId.HC, hcOk,
                when { !hc.available -> hc.status; !hcOk -> "grant sleep access"; hc.backgroundReadSupported && !hc.canReadInBackground() -> "allow background reads"; else -> "reading sleep" },
                null),
            CheckItem(CheckId.USE, UnlockLog.granted(context), "unlock times end a skipped night",
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri)),
        )
    }
}
