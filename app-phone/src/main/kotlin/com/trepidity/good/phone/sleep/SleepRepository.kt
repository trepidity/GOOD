package com.trepidity.good.phone.sleep

import android.content.Context
import com.trepidity.good.model.SleepSummary
import com.trepidity.good.phone.AppGraph
import com.trepidity.good.phone.EventLog
import com.trepidity.good.phone.data.AnchorEntity
import com.trepidity.good.phone.data.GoodDatabase
import com.trepidity.good.phone.data.SleepSegmentEntity
import com.trepidity.good.phone.data.SleepSessionEntity
import com.trepidity.good.phone.data.SleepSignalEntity
import com.trepidity.good.phone.sync.PhoneSync
import com.trepidity.good.sleep.Anchors
import com.trepidity.good.sleep.Night
import com.trepidity.good.sleep.NightWindow
import com.trepidity.good.sleep.Sample
import com.trepidity.good.sleep.SessionBuilder
import com.trepidity.good.sleep.SleepSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One sleep session per night, merged from Health Connect (OHealth), watch passive state, the phone Sleep API
 * and the bed/dismiss anchors (SPEC Sleep tracking). Pure decisions live in :core:sleep; this class does I/O.
 */
class SleepRepository(private val context: Context, private val db: GoodDatabase) {
    val healthConnect = HealthConnectGateway(context)
    private val dao = db.sleep()
    private val mutex = Mutex()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun recent(limit: Int = 30): Flow<List<SleepSessionEntity>> = dao.observeRecent(limit)

    suspend fun segments(sessionId: Long): List<SleepSegmentEntity> = dao.segments(sessionId)

    suspend fun recordBedtime(at: Instant, from: String) {
        dao.insertAnchor(AnchorEntity(at = at.toEpochMilli(), kind = AnchorEntity.BED))
        EventLog.log(context, "BEDTIME", "from $from")
    }

    suspend fun recordWatchSignal(at: Instant, asleep: Boolean) =
        dao.insertSignal(SleepSignalEntity(at = at.toEpochMilli(), source = SleepSource.WATCH.name, asleep = asleep, confidence = null))

    suspend fun recordPhoneSignal(at: Instant, asleep: Boolean, confidence: Int?) =
        dao.insertSignal(SleepSignalEntity(at = at.toEpochMilli(), source = SleepSource.PHONE.name, asleep = asleep, confidence = confidence))

    /** Alarm dismissed: anchor the wake time, write the provisional session, and queue the Health Connect re-reads. */
    suspend fun onWake(at: Instant) {
        dao.insertAnchor(AnchorEntity(at = at.toEpochMilli(), kind = AnchorEntity.WAKE))
        val date = NightWindow.wakeDateOf(at, zone) ?: return
        rebuild(date)
        SleepSyncWorker.scheduleAfterWake(context, date, at)
    }

    /** Re-reads the last few nights; called whenever the app opens, as the foreground fallback for REVIEW P1. */
    suspend fun syncRecent(nights: Int = 3) {
        val today = LocalDate.now(zone)
        for (i in 0 until nights) rebuild(today.minusDays(i.toLong()))
        publishSummary()
    }

    /** Builds the night's best session and stores it unless the stored one wins (edited, or higher priority). */
    suspend fun rebuild(wakeDate: LocalDate): SleepSessionEntity? = mutex.withLock {
        val window = NightWindow.forWakeDate(wakeDate, zone)
        val from = window.start.toEpochMilli()
        val to = window.end.toEpochMilli()
        val external = healthConnect.readSessions(window)
        val samples = dao.signals(from, to).map { Sample(Instant.ofEpochMilli(it.at), it.asleep, SleepSource.valueOf(it.source)) }
        val anchorRows = dao.anchors(from, to)
        val dismiss = anchorRows.lastOrNull { it.kind == AnchorEntity.WAKE }?.at
        val bed = anchorRows.lastOrNull { it.kind == AnchorEntity.BED && (dismiss == null || it.at < dismiss) }?.at
        val anchors = Anchors(bed?.let(Instant::ofEpochMilli), dismiss?.let(Instant::ofEpochMilli))

        val existing = dao.session(wakeDate.toString())
        val draft = SessionBuilder.build(window, external, samples, anchors, context.packageName) ?: return@withLock existing
        if (existing != null && !SessionBuilder.shouldReplace(SleepSource.valueOf(existing.source), existing.edited, draft)) return@withLock existing

        var hcId = draft.externalId
        if (draft.source == SleepSource.HEALTH_CONNECT) {
            healthConnect.deleteOwn(wakeDate.toString())
        } else {
            hcId = healthConnect.writeOwn(wakeDate.toString(), draft) ?: existing?.healthConnectId
        }
        val entity = SleepSessionEntity(
            id = existing?.id ?: 0,
            wakeDate = wakeDate.toString(),
            start = draft.start.toEpochMilli(),
            end = draft.end.toEpochMilli(),
            source = draft.source.name,
            sourcePackage = draft.sourcePackage,
            edited = false,
            healthConnectId = hcId,
            totalSleepMin = draft.totalSleepMin,
            awakenings = draft.awakenings,
            bedtimeAnchor = bed,
        )
        val id = dao.replace(entity, draft.segments.map { SleepSegmentEntity(sessionId = 0, start = it.start.toEpochMilli(), end = it.end.toEpochMilli(), kind = it.kind.name) })
        if (existing?.source != draft.source.name) EventLog.log(context, "SLEEP_SESSION", "$wakeDate from ${draft.source} (${draft.totalSleepMin} min)")
        prune()
        entity.copy(id = id)
    }

    /** Hand edit (SPEC step 6): marked edited, so later syncs never overwrite it. */
    suspend fun edit(wakeDate: LocalDate, start: Instant, end: Instant) = mutex.withLock {
        if (!end.isAfter(start)) return@withLock
        val existing = dao.session(wakeDate.toString())
        val awakeMin = existing?.let { s -> dao.segments(s.id).filter { it.kind == "AWAKE" }.sumOf { (it.end - it.start) / 60_000 } } ?: 0
        val total = (Duration.between(start, end).toMinutes() - awakeMin).toInt().coerceAtLeast(0)
        val entity = (existing ?: SleepSessionEntity(
            wakeDate = wakeDate.toString(), start = 0, end = 0, source = SleepSource.ANCHORS.name, sourcePackage = null,
            edited = true, healthConnectId = null, totalSleepMin = 0, awakenings = 0, bedtimeAnchor = null,
        )).copy(start = start.toEpochMilli(), end = end.toEpochMilli(), edited = true, totalSleepMin = total)
        if (existing == null) dao.replace(entity, emptyList()) else dao.updateSession(entity)
        EventLog.log(context, "SLEEP_EDITED", "$wakeDate")
    }

    /** The nights the metrics are computed from. Bedtime is the bed button when pressed, else sleep start. */
    suspend fun nights(): List<Night> = dao.allSessions().map {
        Night(LocalDate.parse(it.wakeDate), it.totalSleepMin, Instant.ofEpochMilli(it.bedtimeAnchor ?: it.start))
    }

    suspend fun publishSummary() {
        val last = dao.allSessions().lastOrNull()
        val summary = SleepSummary(
            wakeDate = last?.wakeDate,
            totalSleepMin = last?.totalSleepMin,
            bedtimeEpochMs = last?.let { it.bedtimeAnchor ?: it.start },
            wakeEpochMs = last?.end,
            goalMin = AppGraph.prefs(context).sleepGoalMin,
            fromHealthConnect = last?.source == SleepSource.HEALTH_CONNECT.name,
        )
        runCatching { PhoneSync.pushSleepSummary(context, summary) }
    }

    fun ensureSleepApi() = SleepApiReceiver.subscribe(context)

    private suspend fun prune() {
        val now = System.currentTimeMillis()
        dao.pruneSignals(now - Duration.ofDays(14).toMillis())
        db.events().prune(now - Duration.ofDays(30).toMillis())
    }
}
