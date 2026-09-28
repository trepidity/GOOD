package com.trepidity.good.phone.spike

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.trepidity.good.phone.alarm.AlarmScheduler

/** One line of the Reliability check screen: what, whether it's OK, and where to fix it. */
data class CheckItem(val name: String, val ok: Boolean, val fix: Intent?)

object ReliabilityCheck {
    fun run(context: Context): List<CheckItem> {
        val pkg = context.packageName
        val pkgUri = Uri.parse("package:$pkg")
        val items = mutableListOf<CheckItem>()

        items += CheckItem(
            "Exact alarms",
            AlarmScheduler.canScheduleExact(context),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri) else null,
        )
        items += CheckItem(
            "Full-screen alarm screen",
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri) else null,
        )
        items += CheckItem(
            "Notifications",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
        )
        items += CheckItem(
            "Battery: unrestricted",
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        items += CheckItem(
            "Background not restricted",
            !context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
        )
        return items
    }
}
