package com.trepidity.good.phone.spike

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * M0 question: does OHealth actually write sleep sessions (and stages) to Health Connect, and how soon?
 */
object HealthConnectProbe {
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
    )

    fun status(context: Context): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "needs a Health Connect update"
        else -> "not available on this phone"
    }

    fun isAvailable(context: Context) = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(context: Context): Boolean =
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /** One line per sleep session in the last [days] days, with its source app. */
    suspend fun recentSleep(context: Context, days: Long = 7): List<String> {
        val client = HealthConnectClient.getOrCreate(context)
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(days))),
            )
        )
        val fmt = DateTimeFormatter.ofPattern("EEE MMM d HH:mm").withZone(ZoneId.systemDefault())
        return response.records.map { r ->
            val mins = Duration.between(r.startTime, r.endTime).toMinutes()
            "${fmt.format(r.startTime)} → ${fmt.format(r.endTime)} · ${mins / 60}h${"%02d".format(mins % 60)}m · " +
                "${r.stages.size} stages · from ${r.metadata.dataOrigin.packageName}"
        }.ifEmpty { listOf("No sleep sessions in the last $days days") }
    }
}
