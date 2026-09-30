package com.trepidity.good.wear.surface

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.em
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.FontSetting
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.trepidity.good.model.BedtimeMessage
import com.trepidity.good.sleep.SleepAction
import com.trepidity.good.sync.DataLayerPaths
import com.trepidity.good.sync.SyncCodec
import com.trepidity.good.wear.WatchFormat
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.sleep.WatchImUp
import com.trepidity.good.wear.sync.WatchSync
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The LCD strip tile: `AL1 6:30` and `SLP 7:42` on the grey-green panel, with a BED/UP button that logs bedtime or
 * I'M UP (through the outbox, so it works out of range) and answers "GOOD NIGHT" or "GOOD MORNING" for that render.
 */
class GoodTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val pressed = requestParams.currentState.lastClickableId == CLICK_BED
        val lines = when {
            !pressed -> listOf(alarmLine(), sleepLine())
            else -> sleepButton()
        }
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(30 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(lines)))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    /** BED or UP, once per press; a refresh repeating the last click id within a minute doesn't log it again. */
    private fun sleepButton(): List<String> {
        val now = System.currentTimeMillis()
        if (now - lastBedAt < 60_000) return lastLines
        lastBedAt = now
        lastLines = if (WatchScheduleStore.sleepAction(this) == SleepAction.UP) {
            WatchImUp.record(this, Instant.ofEpochMilli(now))
            listOf("GOOD", "MORNING")
        } else {
            WatchScheduleStore.recordLocalBed(this, now)
            (application as WearApplication).appScope.launch {
                WatchSync.send(this@GoodTileService, DataLayerPaths.SLEEP_BEDTIME, SyncCodec.encodeAny(BedtimeMessage(now)))
            }
            listOf("GOOD", "NIGHT")
        }
        return lastLines
    }

    private fun alarmLine(): String {
        val next = WatchScheduleStore.next(this) ?: return "AL- --:--"
        return "${WatchFormat.channel(next.instance.alarmId)} ${WatchFormat.clock(this, next.instance.scheduledAtEpochMs)}"
    }

    private fun sleepLine(): String {
        val total = (WatchScheduleStore.sleepSummary.value ?: WatchScheduleStore.summary(this))?.totalSleepMin
        return "SLP ${total?.let(WatchFormat::hoursMinutes) ?: "-:--"}"
    }

    private fun layout(lines: List<String>): LayoutElementBuilders.LayoutElement {
        val strip = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(PANEL))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(14f)).build())
                            .build(),
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(12f)).build())
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(lines.joinToString(", ")).build())
                    .build(),
            )
        lines.forEach { strip.addContent(text(it, 26f, INK)) }

        val up = WatchScheduleStore.sleepAction(this) == SleepAction.UP
        val bedButton = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(96f))
            .setHeight(dp(48f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(CASE))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(24f)).build())
                            .build(),
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(CLICK_BED)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build(),
                    )
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(if (up) "Log wake-up now" else "Log bedtime now").build())
                    .build(),
            )
            .addContent(text(if (up) "UP" else "BED", 16f, LABEL))
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(strip.build())
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(12f)).build())
                    .addContent(bedButton)
                    .build(),
            )
            .build()
    }

    private fun text(value: String, size: Float, color: Int) = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setMaxLines(1)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                .setLetterSpacing(em(0.06f))
                .setSettings(FontSetting.tabularNum())
                .build(),
        )
        .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val CLICK_BED = "bed"
        const val PANEL = 0xFFA7B39A.toInt()
        const val INK = 0xFF1B2116.toInt()
        const val CASE = 0xFF2B2E2A.toInt()
        const val LABEL = 0xFFC4C9BD.toInt()

        @Volatile var lastBedAt = 0L
        @Volatile var lastLines = listOf("GOOD", "NIGHT")
    }
}
