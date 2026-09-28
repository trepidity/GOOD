package com.trepidity.good.phone.wake

import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.trepidity.good.lcd.CaseButton
import com.trepidity.good.lcd.LcdPalette
import com.trepidity.good.lcd.LcdPanel
import com.trepidity.good.lcd.SegmentBar
import com.trepidity.good.lcd.SegmentKind
import com.trepidity.good.lcd.SegmentText
import com.trepidity.good.lcd.sunrise
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * Full-screen ringing UI. The LCD panel is the sunrise: its backlight warms from deep red through amber to white
 * over the light stage while the digits stay dark, and the screen brightness ramps 1% → 100% with it.
 * Dismiss = hold STOP for 2 s while a segment bar fills; armed from the first stage (REVIEW U1). No snooze.
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { RingScreen() }
    }

    @Composable
    private fun RingScreen() {
        val ui by WakeService.ui.collectAsState()
        var seen by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var clock by remember { mutableStateOf(now()) }
        var held by remember { mutableFloatStateOf(0f) }

        // Close only when ringing ends — never on the initial null before the service publishes (REVIEW R3).
        LaunchedEffect(ui) {
            val current = ui
            if (current == null) {
                if (!seen) delay(5_000)
                if (WakeService.ui.value == null) finish()
                return@LaunchedEffect
            }
            seen = true
            while (true) {
                progress = lightProgress(current, Instant.now())
                clock = now()
                setBrightness(if (current.lightStart == null) -1f else 0.01f + 0.99f * progress)
                delay(1_000)
            }
        }

        val palette = LcdPalette.sunrise(progress)
        Box(Modifier.fillMaxSize().background(palette.caseColor)) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LcdPanel(palette, Modifier.fillMaxWidth().weight(1f)) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        SegmentText(ui?.label?.uppercase()?.take(12) ?: "", 22.dp, palette, kind = SegmentKind.FOURTEEN)
                        SegmentText(clock, 120.dp, palette, contentDescription = "Time $clock")
                        SegmentText(if (ui?.preview == true) "PREVIEW" else "GOOD MORNING", 20.dp, palette, kind = SegmentKind.FOURTEEN)
                        SegmentBar(held, 12, palette, Modifier.fillMaxWidth().height(16.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                // Big target: a half-asleep hand only has to find the lower half of the screen and hold.
                // The face takes the backlight's colour, so it is findable in the dark and never white at night.
                CaseButton(
                    "HOLD STOP", palette.copy(caseColor = lerp(palette.panel, Color.Black, 0.45f), label = lerp(palette.panel, Color.White, 0.3f)),
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().height(132.dp),
                    faceHeight = 96.dp,
                    onLongClick = ::dismiss,
                    onHoldProgress = { held = it },
                    contentDescription = "Stop alarm. Press and hold for two seconds.",
                )
            }
        }
    }

    private fun dismiss() {
        startService(WakeService.actionIntent(this, WakeService.ACTION_DISMISS))
        finish()
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun lightProgress(ui: RingUi, t: Instant): Float {
        val start = ui.lightStart ?: return 1f
        val end = ui.lightEnd ?: return 1f
        val total = Duration.between(start, end).toMillis().coerceAtLeast(1)
        return (Duration.between(start, t).toMillis().toFloat() / total).coerceIn(0f, 1f)
    }

    private fun now(): String = LocalTime.now().let {
        val h = if (DateFormat.is24HourFormat(this)) it.hour else (it.hour % 12).let { h -> if (h == 0) 12 else h }
        "$h:${"%02d".format(it.minute)}"
    }
}
