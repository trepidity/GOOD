package com.trepidity.good.wear.wake

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.min
import com.trepidity.good.lcd.LcdPalette
import com.trepidity.good.lcd.SegmentKind
import com.trepidity.good.lcd.SegmentRing
import com.trepidity.good.lcd.SegmentText
import com.trepidity.good.wear.WatchFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Black screen with the time in amber segments (so it doesn't light the room). From the first stage, press and
 * hold anywhere for 2 s: 60 segments fill around the round edge like a seconds track, ticking as they go (REVIEW U1).
 * No snooze.
 */
class WatchRingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { Screen() }
    }

    @Composable
    private fun Screen() {
        val ui by WakeStageService.ui.collectAsState()
        var clock by remember { mutableStateOf(now()) }
        var seen by remember { mutableStateOf(false) }
        val armed = ui != null
        var held by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        val view = LocalView.current

        // Close only when ringing ends, never on the initial null before the service publishes (REVIEW R3).
        LaunchedEffect(ui) {
            if (ui == null) {
                if (seen) finish() else { delay(5_000); if (WakeStageService.ui.value == null) finish() }
                return@LaunchedEffect
            }
            seen = true
            while (true) {
                clock = now()
                delay(1_000)
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clearAndSetSemantics {
                    contentDescription = if (armed) "Alarm ringing, $clock. Press and hold anywhere for 2 seconds to stop." else clock
                    if (armed) onLongClick("Stop alarm") { dismiss(); true }
                }
                .pointerInput(armed) {
                    if (!armed) return@pointerInput
                    detectTapGestures(onPress = {
                        val job = scope.launch {
                            val start = System.currentTimeMillis()
                            var ticks = 0
                            while (held < 1f) {
                                held = ((System.currentTimeMillis() - start) / HOLD_MS.toFloat()).coerceAtMost(1f)
                                val tick = (held * 10).toInt()
                                if (tick > ticks) {
                                    ticks = tick
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                delay(16)
                            }
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            dismiss()
                        }
                        tryAwaitRelease()
                        if (held < 1f) { job.cancel(); held = 0f }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            val s = min(maxWidth, maxHeight)
            if (armed) SegmentRing(held, AMBER, Modifier.fillMaxSize().padding(s * 0.02f), count = 60, ghost = AMBER.copy(alpha = 0.12f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(s * 0.05f)) {
                val label = ui?.label?.uppercase(Locale.ROOT)?.take(12).orEmpty()
                if (label.isNotBlank()) SegmentText(label, s * 0.06f, RING, kind = SegmentKind.FOURTEEN, contentDescription = null)
                SegmentText(clock, s * 0.26f, RING, contentDescription = null)
                if (armed) SegmentText("HOLD", s * 0.06f, RING, kind = SegmentKind.FOURTEEN, contentDescription = null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    private fun dismiss() {
        startService(WakeStageService.actionIntent(this, WakeStageService.ACTION_DISMISS))
        finish()
    }

    private fun now(): String = WatchFormat.clock(this, System.currentTimeMillis())

    companion object {
        /** Whether the ringing screen is in front; the ring service brings it back when it isn't. */
        @Volatile var visible = false
            private set

        private val AMBER = Color(0xFFFFB38A)

        /** Black glass, amber segments, unlit segments barely there: nothing bright at night. */
        private val RING = LcdPalette(
            panel = Color.Black,
            ink = AMBER,
            ghost = AMBER.copy(alpha = 0.05f),
            caseColor = Color.Black,
            label = AMBER.copy(alpha = 0.6f),
            isNight = true,
        )
        const val HOLD_MS = 2_000L
    }
}
