package com.trepidity.good.phone.wake

import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Full-screen ringing UI. Tracks the light ramp: screen brightness 1% → 100% and a colour sweep
 * from deep red through amber to warm white. The dismiss slider appears once the sound stage begins.
 * There is no snooze, by design.
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
        var progress by remember { mutableFloatStateOf(0f) }
        var showControls by remember { mutableStateOf(false) }
        var clock by remember { mutableStateOf(now()) }

        LaunchedEffect(ui) {
            val current = ui ?: run { finish(); return@LaunchedEffect }
            while (true) {
                val t = Instant.now()
                progress = lightProgress(current, t)
                showControls = !t.isBefore(current.controlsAt)
                clock = now()
                setBrightness(if (current.lightStart == null) -1f else 0.01f + 0.99f * progress)
                delay(1_000)
            }
        }

        val bg = when {
            progress < 0.5f -> lerp(DEEP_RED, AMBER, progress / 0.5f)
            else -> lerp(AMBER, WARM_WHITE, (progress - 0.5f) / 0.5f)
        }
        val ink = if (progress > 0.65f) Color(0xFF2B1A10) else Color(0xFFFFE8D6)

        Box(Modifier.fillMaxSize().background(bg).padding(24.dp)) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(96.dp))
                    Text(clock, color = ink, fontSize = 88.sp)
                    Text(ui?.label.orEmpty(), color = ink, fontSize = 20.sp)
                }
                if (showControls) Controls(ink)
            }
        }
    }

    @Composable
    private fun Controls(ink: Color) {
        var slide by remember { mutableFloatStateOf(0f) }
        Column(Modifier.fillMaxWidth().padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The only control: a deliberate full-width slide, so a half-asleep tap can't end the alarm.
            Text("Slide to dismiss", color = ink, fontSize = 24.sp)
            Slider(
                value = slide,
                onValueChange = { slide = it },
                onValueChangeFinished = { if (slide > 0.95f) send(WakeService.ACTION_DISMISS) else slide = 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private fun send(action: String) {
        startService(WakeService.actionIntent(this, action))
        finish()
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun lightProgress(ui: RingUi, t: Instant): Float {
        val start = ui.lightStart ?: return 0f
        val end = ui.lightEnd ?: return 0f
        val total = Duration.between(start, end).toMillis().coerceAtLeast(1)
        return (Duration.between(start, t).toMillis().toFloat() / total).coerceIn(0f, 1f)
    }

    private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm"))

    private companion object {
        val DEEP_RED = Color(0xFF1A0200)
        val AMBER = Color(0xFFB34A00)
        val WARM_WHITE = Color(0xFFFFF2DE)
    }
}
