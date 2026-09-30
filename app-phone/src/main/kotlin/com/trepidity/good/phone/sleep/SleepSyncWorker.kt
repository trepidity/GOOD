package com.trepidity.good.phone.sleep

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trepidity.good.phone.AppGraph
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Re-reads Health Connect for one night: at dismiss +30 min, +2 h and at 11:00 (SPEC Sleep tracking step 3), plus a
 * daily 11:00 pass.
 */
class SleepSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = AppGraph.sleep(applicationContext)
        val date = inputData.getString(KEY_DATE)?.let(LocalDate::parse)
        if (date == null) repo.syncRecent(nights = 2) else repo.rebuild(date)
        return Result.success()
    }

    companion object {
        private const val KEY_DATE = "wakeDate"

        /**
         * A daily pass around 11:00 over the last two nights, so a night with no dismiss (skipped, or no alarm)
         * still picks up Health Connect data and an inferred wake without the app being opened.
         */
        fun scheduleDaily(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(11, 0).atZone(zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val req = PeriodicWorkRequestBuilder<SleepSyncWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sleep-daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun scheduleAfterWake(context: Context, wakeDate: LocalDate, wokeAt: Instant) {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val eleven = wakeDate.atTime(11, 0).atZone(zone).toInstant()
            val runs = listOf(
                "30m" to wokeAt.plus(Duration.ofMinutes(30)),
                "2h" to wokeAt.plus(Duration.ofHours(2)),
                "11" to eleven,
            ).filter { it.second.isAfter(now) }
            val wm = WorkManager.getInstance(context)
            for ((tag, at) in runs) {
                val req = OneTimeWorkRequestBuilder<SleepSyncWorker>()
                    .setInitialDelay(Duration.between(now, at).toMillis(), TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_DATE to wakeDate.toString()))
                    .build()
                wm.enqueueUniqueWork("sleep-$wakeDate-$tag", ExistingWorkPolicy.REPLACE, req)
            }
        }
    }
}
