package com.trepidity.good.wear.surface

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.trepidity.good.wear.WatchFormat
import com.trepidity.good.wear.alarm.WatchScheduleStore

private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()

private fun shortText(text: String, title: String, description: String): ComplicationData =
    ShortTextComplicationData.Builder(plain(text), plain(description)).setTitle(plain(title)).build()

/** Next alarm: SHORT_TEXT "6:30" titled "AL1". Pushed by [WatchSurfaces], never polled. */
class NextAlarmComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return NoDataComplicationData()
        val next = WatchScheduleStore.next(this) ?: return shortText("--:--", "AL-", "No alarm set")
        val at = next.instance.scheduledAtEpochMs
        val time = WatchFormat.clock(this, at)
        val channel = WatchFormat.channel(next.instance.alarmId)
        return shortText(time, channel, "Next alarm $channel at $time")
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText("6:30", "AL1", "Next alarm AL1 at 6:30") else null
}

/** Last night's sleep against the goal: RANGED_VALUE 0..goal with "7:42", or SHORT_TEXT. */
class SleepComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val summary = WatchScheduleStore.sleepSummary.value ?: WatchScheduleStore.summary(this)
        return data(request.complicationType, summary?.totalSleepMin, summary?.goalMin ?: 480)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, 462, 480)

    private fun data(type: ComplicationType, totalMin: Int?, goalMin: Int): ComplicationData {
        val text = totalMin?.let(WatchFormat::hoursMinutes) ?: "-:--"
        val description = totalMin?.let { "Slept ${it / 60} hours ${it % 60} minutes of ${goalMin / 60} hour goal" } ?: "No sleep data"
        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                val max = goalMin.coerceAtLeast(1).toFloat()
                RangedValueComplicationData.Builder((totalMin ?: 0).toFloat().coerceIn(0f, max), 0f, max, plain(description))
                    .setText(plain(text))
                    .setTitle(plain("SLP"))
                    .build()
            }
            ComplicationType.SHORT_TEXT -> shortText(text, "SLP", description)
            else -> NoDataComplicationData()
        }
    }
}
