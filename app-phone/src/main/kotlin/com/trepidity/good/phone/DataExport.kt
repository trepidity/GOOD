package com.trepidity.good.phone

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** CHK → EXP: the opt-in JSON export (SPEC Data model rules). Written only to a file the user picks. */
object DataExport {
    @Serializable
    private data class Export(
        val exportedAtEpochMs: Long,
        val alarms: List<Map<String, String?>>,
        val instances: List<Map<String, String?>>,
        val sleepSessions: List<Map<String, String?>>,
        val sleepSegments: List<Map<String, String?>>,
        val events: List<Map<String, String?>>,
    )

    private val json = Json { prettyPrint = true }

    suspend fun write(context: Context, uri: Uri): Int {
        val db = AppGraph.db(context)
        val export = Export(
            exportedAtEpochMs = System.currentTimeMillis(),
            alarms = db.alarms().all().map { mapOf("id" to "${it.id}", "hour" to "${it.hour}", "minute" to "${it.minute}", "repeatDays" to "${it.repeatDays}", "enabled" to "${it.enabled}", "profileId" to it.profileId, "tone" to it.tone, "soundTarget" to it.soundTarget) },
            instances = db.instances().all().map { mapOf("id" to it.id, "alarmId" to "${it.alarmId}", "scheduledAt" to "${it.scheduledAt}", "state" to it.state, "dismissedAt" to it.dismissedAt?.toString(), "dismissedOn" to it.dismissedOn, "firstStageAt" to it.firstStageAt?.toString(), "dismissedAtStage" to it.dismissedAtStage) },
            sleepSessions = db.sleep().allSessions().map { mapOf("id" to "${it.id}", "wakeDate" to it.wakeDate, "start" to "${it.start}", "end" to "${it.end}", "source" to it.source, "sourcePackage" to it.sourcePackage, "edited" to "${it.edited}", "totalSleepMin" to "${it.totalSleepMin}", "awakenings" to "${it.awakenings}") },
            sleepSegments = db.sleep().allSegments().map { mapOf("sessionId" to "${it.sessionId}", "start" to "${it.start}", "end" to "${it.end}", "kind" to it.kind) },
            events = db.events().recent(5_000).map { mapOf("at" to "${it.at}", "device" to it.device, "type" to it.type, "detail" to it.detail) },
        )
        val text = json.encodeToString(Export.serializer(), export)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
        return export.sleepSessions.size
    }
}
