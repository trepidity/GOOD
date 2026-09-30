package com.trepidity.good.phone.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.trepidity.good.model.Alarm
import com.trepidity.good.model.AlarmInstance
import com.trepidity.good.model.Device
import com.trepidity.good.model.InstanceState
import com.trepidity.good.model.SoundTarget
import com.trepidity.good.model.Stage
import com.trepidity.good.model.StageType
import com.trepidity.good.model.Tone
import com.trepidity.good.model.WakeProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One of the four alarm channels AL1–AL4; [id] is the channel number. */
@Entity(tableName = "alarm")
data class AlarmEntity(
    @PrimaryKey val id: Long,
    val hour: Int,
    val minute: Int,
    val repeatDays: Int,
    val label: String,
    val enabled: Boolean,
    val profileId: String,
    val soundTarget: String,
    val tone: String,
    val skipNextDate: String?,
) {
    fun toModel() = Alarm(
        id, hour, minute, repeatDays, label, enabled, profileId,
        SoundTarget.valueOf(soundTarget), skipNextDate, Tone.valueOf(tone),
    )

    companion object {
        fun of(a: Alarm) = AlarmEntity(
            a.id, a.hour, a.minute, a.repeatDays, a.label, a.enabled, a.profileId, a.soundTarget.name, a.tone.name, a.skipNextDate,
        )
    }
}

@Entity(tableName = "wake_profile")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val stagesJson: String,
    val autoSilenceMinutes: Int,
) {
    fun toModel() = WakeProfile(id, name, json.decodeFromString(STAGES, stagesJson), autoSilenceMinutes)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val STAGES = ListSerializer(Stage.serializer())
        fun of(p: WakeProfile) = ProfileEntity(p.id, p.name, json.encodeToString(STAGES, p.stages), p.autoSilenceMinutes)
    }
}

@Entity(tableName = "alarm_instance", indices = [Index("alarmId"), Index("scheduledAt")])
data class InstanceEntity(
    @PrimaryKey val id: String,
    val alarmId: Long,
    val scheduledAt: Long,
    val state: String,
    val currentStage: String?,
    val dismissedAt: Long?,
    val dismissedOn: String?,
    val firstStageAt: Long? = null,
    val dismissedAtStage: String? = null,
) {
    fun toModel() = AlarmInstance(
        id, alarmId, scheduledAt, InstanceState.valueOf(state),
        currentStage?.let(StageType::valueOf), dismissedAt, dismissedOn?.let(Device::valueOf),
        firstStageAt, dismissedAtStage?.let(StageType::valueOf),
    )

    companion object {
        fun of(i: AlarmInstance) = InstanceEntity(
            i.id, i.alarmId, i.scheduledAtEpochMs, i.state.name, i.currentStage?.name, i.dismissedAtEpochMs, i.dismissedOn?.name,
            i.firstStageAtEpochMs, i.dismissedAtStage?.name,
        )
    }
}

/** One night. [source] is a core.sleep SleepSource name; [edited] sessions are never overwritten by a sync. */
@Entity(tableName = "sleep_session", indices = [Index(value = ["wakeDate"], unique = true)])
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wakeDate: String,
    val start: Long,
    val end: Long,
    val source: String,
    val sourcePackage: String?,
    val edited: Boolean,
    /** Id of the Health Connect record this session came from, or that GOOD wrote for it. */
    val healthConnectId: String?,
    val totalSleepMin: Int,
    val awakenings: Int,
    val bedtimeAnchor: Long?,
)

@Entity(
    tableName = "sleep_segment",
    foreignKeys = [ForeignKey(entity = SleepSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SleepSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val start: Long,
    val end: Long,
    val kind: String,
)

/** Raw asleep/awake samples from the phone Sleep API or the watch; pruned after 14 days. */
@Entity(tableName = "sleep_signal", indices = [Index("at")])
data class SleepSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val source: String,
    val asleep: Boolean,
    val confidence: Int?,
)

/** Bed-button presses and alarm dismissals, the fallback start and end of a night. */
@Entity(tableName = "sleep_anchor", indices = [Index("at")])
data class AnchorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
) {
    companion object {
        const val BED = "BED"
        const val WAKE = "WAKE"
    }
}

/** Diagnostics; pruned after 30 days. */
@Entity(tableName = "event_log", indices = [Index("at")])
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val device: String,
    val type: String,
    val detail: String,
)
