package com.trepidity.good.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.ui.WatchApp

/** The watch app: one round LCD in four modes (ALM · SLP · PRO · CHK), all views of the phone's data. */
class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening GOOD clears a force stop; re-register what the stored schedule says (REVIEW F1).
        com.trepidity.good.wear.alarm.WatchScheduleStore.load(this)?.let { com.trepidity.good.wear.alarm.WatchAlarmScheduler.apply(this, it) }
        WatchScheduleStore.load(this)
        WatchScheduleStore.summary(this)
        setContent { WatchApp(resumes, onExit = ::finish) }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }
}
