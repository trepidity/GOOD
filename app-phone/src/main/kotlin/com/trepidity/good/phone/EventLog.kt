package com.trepidity.good.phone

import android.content.Context
import android.util.Log
import com.trepidity.good.phone.data.EventLogEntity
import kotlinx.coroutines.launch

/** Diagnostics for the M5 burn-in review. Before first unlock the database is unavailable, so events go to logcat only. */
object EventLog {
    private const val TAG = "GoodEvent"

    fun log(context: Context, type: String, detail: String = "", device: String = "PHONE") {
        Log.i(TAG, "$device $type $detail")
        if (!AppGraph.isUnlocked(context)) return
        val app = context.applicationContext as GoodApplication
        app.appScope.launch {
            runCatching { AppGraph.db(app).events().insert(EventLogEntity(at = System.currentTimeMillis(), device = device, type = type, detail = detail)) }
        }
    }
}
