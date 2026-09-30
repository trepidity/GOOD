package com.trepidity.good.wear.surface

import androidx.core.content.edit
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
import com.trepidity.good.wear.MainActivity
import com.trepidity.good.wear.WatchFormat
import com.trepidity.good.wear.WearApplication
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.sync.WatchSync
import com.trepidity.good.wear.ui.Mode
import kotlinx.coroutines.launch

/**
 * The LCD strip tile: `AL1 6:30` and `SLP 7:42` on the grey-green panel, with a BED/UP button. BED is a tap: it logs
 * bedtime (through the outbox, so it works out of range) and answers "GOOD NIGHT" for that render. UP only opens the
 * app in SLP: GOOD MORNING closes this morning's alarms on both devices, so it takes the app's 2 s hold, and a tile
 * (tap only) must not be able to do it.
 */
class GoodTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val lines = (if (claimPress(requestParams.currentState.lastClickableId)) goodNight() else null)
            ?: listOf(alarmLine(), sleepLine())
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(30 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(lines)))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    /**
     * True once per press. The renderer can repeat the last clicked id on later refreshes (the freshness refresh, or
     * the update a sync requests); a repeat would log a second bedtime, possibly on a later night. So every
     * render gives the button a fresh id, and an id is acted on only the first time it arrives. It is recorded before
     * acting, and in device-protected storage so a killed process doesn't forget it.
     */
    private fun claimPress(id: String): Boolean {
        if (!id.startsWith(CLICK_BED_PREFIX)) return false
        val prefs = createDeviceProtectedStorageContext().getSharedPreferences(PREFS, MODE_PRIVATE)
        synchronized(lock) {
            if (prefs.getString(KEY_HANDLED, null) == id) return false
            prefs.edit(commit = true) { putString(KEY_HANDLED, id) }
        }
        return true
    }

    /**
     * Logs bedtime and answers GOOD NIGHT for this render, or null (nothing logged) when the toggle now offers UP: a BED
     * press from a render made before the night began (the phone logged bed since) must not start a second night, and
     * the tile never logs I'M UP.
     */
    private fun goodNight(): List<String>? {
        if (WatchScheduleStore.sleepAction(this) == SleepAction.UP) return null
        val now = System.currentTimeMillis()
        WatchScheduleStore.recordLocalBed(this, now)
        (application as WearApplication).appScope.launch {
            WatchSync.send(this@GoodTileService, DataLayerPaths.SLEEP_BEDTIME, SyncCodec.encodeAny(BedtimeMessage(now)))
        }
        return listOf("GOOD", "NIGHT")
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
                    .setClickable(if (up) openSlp() else logBed())
                    .setSemantics(
                        ModifiersBuilders.Semantics.Builder()
                            .setContentDescription(if (up) "Open sleep. Hold SET two seconds to log wake-up" else "Log bedtime now")
                            .build(),
                    )
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

    /** BED: a fresh id per render and a reload, so [onTileRequest] sees the press once ([claimPress]). */
    private fun logBed() = ModifiersBuilders.Clickable.Builder()
        .setId("$CLICK_BED_PREFIX${System.currentTimeMillis()}")
        .setOnClick(ActionBuilders.LoadAction.Builder().build())
        .build()

    /** UP: opens the app in SLP, where GOOD MORNING is the 2 s SET hold. Its id is not [CLICK_BED_PREFIX], so it logs nothing. */
    private fun openSlp() = ModifiersBuilders.Clickable.Builder()
        .setId(CLICK_OPEN_SLP)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .addKeyToExtraMapping(MainActivity.EXTRA_MODE, ActionBuilders.stringExtra(Mode.SLP.name))
                        .build(),
                )
                .build(),
        )
        .build()

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
        const val CLICK_BED_PREFIX = "bed-"
        const val CLICK_OPEN_SLP = "open-slp"
        const val PREFS = "good_tile"
        const val KEY_HANDLED = "handledClickId"
        val lock = Any()
        const val PANEL = 0xFFA7B39A.toInt()
        const val INK = 0xFF1B2116.toInt()
        const val CASE = 0xFF2B2E2A.toInt()
        const val LABEL = 0xFFC4C9BD.toInt()

    }
}
