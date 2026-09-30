package com.trepidity.good.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.trepidity.good.wear.alarm.WatchScheduleStore
import com.trepidity.good.wear.ui.Mode
import com.trepidity.good.wear.ui.OpenIn
import com.trepidity.good.wear.ui.WatchApp

/**
 * The watch app: one round LCD in four modes (ALM · SLP · PRO · CHK), all views of the phone's data. An [EXTRA_MODE]
 * extra opens it in that mode: the tile's UP opens SLP, where GOOD MORNING takes the 2 s hold. singleTop (manifest), so
 * a launch while the app is on top arrives in [onNewIntent] instead of stacking a second LCD.
 */
class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)
    private var openIn by mutableStateOf<OpenIn?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opening GOOD clears a force stop; re-register what the stored schedule says (REVIEW F1).
        com.trepidity.good.wear.alarm.WatchScheduleStore.load(this)?.let { com.trepidity.good.wear.alarm.WatchAlarmScheduler.apply(this, it) }
        WatchScheduleStore.load(this)
        WatchScheduleStore.summary(this)
        // Only a fresh launch: after recreation the saved mode is the user's, not the launch's.
        if (savedInstanceState == null) openFrom(intent)
        setContent { WatchApp(resumes, openIn, onExit = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_MODE)?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: return
        openIn = OpenIn(mode, (openIn?.seq ?: 0) + 1)
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }

    companion object {
        /** A [Mode] name (e.g. "SLP") to open the LCD in. */
        const val EXTRA_MODE = "mode"
    }
}
