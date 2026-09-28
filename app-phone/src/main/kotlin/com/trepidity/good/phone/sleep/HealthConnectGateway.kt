package com.trepidity.good.phone.sleep

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.trepidity.good.sleep.ExternalSession
import com.trepidity.good.sleep.NightWindow
import com.trepidity.good.sleep.Segment
import com.trepidity.good.sleep.SegmentKind
import com.trepidity.good.sleep.SessionDraft
import java.time.ZoneId

/** Health Connect: read everyone's sleep sessions (OHealth's above all), write GOOD's own. */
class HealthConnectGateway(private val context: Context) {

    val available: Boolean get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    val status: String get() = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "needs a Health Connect update"
        else -> "not available"
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    /** Background reads need their own permission where Health Connect supports it (REVIEW P1). */
    val backgroundReadSupported: Boolean
        get() = available && runCatching {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)

    /** What GOOD asks for on the consent screen. */
    fun requestedPermissions(): Set<String> = buildSet {
        addAll(CORE)
        if (backgroundReadSupported) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    suspend fun granted(): Set<String> = if (!available) emptySet() else runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    suspend fun canRead(): Boolean = granted().containsAll(CORE)

    suspend fun canReadInBackground(): Boolean = granted().contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)

    /** Sessions overlapping the night, from any app. Empty when unavailable, not permitted, or refused in the background. */
    suspend fun readSessions(window: NightWindow): List<ExternalSession> {
        if (!available || !canRead()) return emptyList()
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(window.start, window.end)),
            ).records.map { r ->
                ExternalSession(
                    id = r.metadata.id,
                    start = r.startTime,
                    end = r.endTime,
                    sourcePackage = r.metadata.dataOrigin.packageName,
                    stages = r.stages.mapNotNull { st -> kindOf(st.stage)?.let { Segment(st.startTime, st.endTime, it) } },
                )
            }
        }.getOrElse {
            Log.w(TAG, "Health Connect read refused", it)
            emptyList()
        }
    }

    /**
     * Writes (or updates) the session GOOD built itself. The client record id is per night, so re-writing
     * the same night replaces the record instead of duplicating it. Returns the client record id, or null.
     */
    suspend fun writeOwn(wakeDate: String, draft: SessionDraft): String? {
        if (!available || !granted().contains(WRITE)) return null
        val id = clientId(wakeDate)
        val zone = ZoneId.systemDefault()
        return runCatching {
            client.insertRecords(
                listOf(
                    SleepSessionRecord(
                        startTime = draft.start,
                        startZoneOffset = zone.rules.getOffset(draft.start),
                        endTime = draft.end,
                        endZoneOffset = zone.rules.getOffset(draft.end),
                        metadata = Metadata.autoRecorded(Device(type = Device.TYPE_PHONE), id, System.currentTimeMillis()),
                        title = "GOOD",
                        stages = draft.segments.map { SleepSessionRecord.Stage(it.start, it.end, stageOf(it.kind)) },
                    ),
                ),
            )
            id
        }.getOrElse {
            Log.w(TAG, "Health Connect write failed", it)
            null
        }
    }

    /** Removes GOOD's own record for a night that another app's session now covers, so nothing is duplicated. */
    suspend fun deleteOwn(wakeDate: String) {
        if (!available || !granted().contains(WRITE)) return
        runCatching { client.deleteRecords(SleepSessionRecord::class, emptyList(), listOf(clientId(wakeDate))) }
    }

    private fun clientId(wakeDate: String) = "good-sleep-$wakeDate"

    private fun kindOf(stage: Int): SegmentKind? = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SegmentKind.AWAKE
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> SegmentKind.ASLEEP
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SegmentKind.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SegmentKind.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SegmentKind.REM
        else -> null
    }

    private fun stageOf(kind: SegmentKind): Int = when (kind) {
        SegmentKind.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
        SegmentKind.ASLEEP -> SleepSessionRecord.STAGE_TYPE_SLEEPING
        SegmentKind.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        SegmentKind.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
        SegmentKind.REM -> SleepSessionRecord.STAGE_TYPE_REM
    }

    companion object {
        private const val TAG = "GoodHealthConnect"
        private val READ = HealthPermission.getReadPermission(SleepSessionRecord::class)
        private val WRITE = HealthPermission.getWritePermission(SleepSessionRecord::class)
        val CORE: Set<String> = setOf(READ, WRITE)
    }
}
