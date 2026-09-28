package com.trepidity.good.phone.sleep

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.trepidity.good.phone.AppGraph
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Re-reads Health Connect for one night: at dismiss +30 min, +2 h and at 11:00 (SPEC Sleep tracking step 3). */
class SleepSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val date = inputData.getString(KEY_DATE)?.let(LocalDate::parse) ?: return Result.success()
        AppGraph.sleep(applicationContext).rebuild(date)
        return Result.success()
    }

    companion object {
        private const val KEY_DATE = "wakeDate"

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
