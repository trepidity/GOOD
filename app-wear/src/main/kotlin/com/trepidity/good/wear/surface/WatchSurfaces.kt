package com.trepidity.good.wear.surface

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** Asks the tile and complications to redraw after the schedule or sleep summary changes. */
object WatchSurfaces {
    private const val TAG = "GoodSurfaces"

    fun refresh(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(GoodTileService::class.java) }
            .onFailure { Log.w(TAG, "Tile update failed", it) }
        for (source in listOf(NextAlarmComplicationService::class.java, SleepComplicationService::class.java)) {
            runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, source)).requestUpdateAll() }
                .onFailure { Log.w(TAG, "Complication update failed: ${source.simpleName}", it) }
        }
    }
}
