package com.trepidity.good.phone.sleep

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.google.android.gms.location.SleepSegmentRequest
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.GoodApplication
import kotlinx.coroutines.launch
import java.time.Instant

/** Play services Sleep API (sleep source 3): classify events every ~10 min plus a daily segment. */
class SleepApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AppGraph.isUnlocked(context)) return
        val classify = if (SleepClassifyEvent.hasEvents(intent)) SleepClassifyEvent.extractEvents(intent) else emptyList()
        val segments = if (SleepSegmentEvent.hasEvents(intent)) SleepSegmentEvent.extractEvents(intent) else emptyList()
        if (classify.isEmpty() && segments.isEmpty()) return
        val pending = goAsync()
        (context.applicationContext as GoodApplication).appScope.launch {
            try {
                val repo = AppGraph.sleep(context)
                classify.forEach { repo.recordPhoneSignal(Instant.ofEpochMilli(it.timestampMillis), it.confidence >= ASLEEP_CONFIDENCE, it.confidence) }
                segments.filter { it.status == SleepSegmentEvent.STATUS_SUCCESSFUL }.forEach {
                    repo.recordPhoneSignal(Instant.ofEpochMilli(it.startTimeMillis), true, null)
                    repo.recordPhoneSignal(Instant.ofEpochMilli(it.endTimeMillis), false, null)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Google's guidance: confidence ≥ 75 is likely asleep. */
        private const val ASLEEP_CONFIDENCE = 75

        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        /** Idempotent: the same PendingIntent replaces any earlier subscription. */
        @SuppressLint("MissingPermission")
        fun subscribe(context: Context) {
            if (!hasPermission(context)) return
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(context, SleepApiReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // Play services fills in the events
            )
            runCatching {
                ActivityRecognition.getClient(context).requestSleepSegmentUpdates(pi, SleepSegmentRequest.getDefaultSleepSegmentRequest())
            }
        }
    }
}
