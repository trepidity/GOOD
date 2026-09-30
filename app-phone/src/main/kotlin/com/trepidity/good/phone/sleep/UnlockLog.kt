package com.trepidity.good.phone.sleep

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.Instant

/**
 * Phone unlocks (keyguard dismissed), read after the fact from usage stats, so no receiver has to be running
 * overnight. Needs Usage access (CHK → USE); without it there are no unlocks and only watch "awake" samples count (#1).
 */
object UnlockLog {
    fun granted(context: Context): Boolean =
        context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    fun unlocks(context: Context, from: Long, to: Long): List<Instant> {
        if (!granted(context)) return emptyList()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, to) ?: return emptyList()
        val out = mutableListOf<Instant>()
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) if (e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) out += Instant.ofEpochMilli(e.timeStamp)
        return out
    }
}
