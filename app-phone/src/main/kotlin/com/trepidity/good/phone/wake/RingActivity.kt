package com.trepidity.good.phone.wake

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * from deep red through amber to warm white. HOLD STOP appears once the sound stage begins.
 * There is no snooze, by design. (The LCD design language from docs/SPEC.md replaces this spike UI in M1.)
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

    /** The only control: press and hold STOP for 2 s while a segment bar fills. Releasing early does nothing. */
    @Composable
    private fun Controls(ink: Color) {
        var held by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxWidth().padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(SEGMENTS) { i ->
                    val lit = held * SEGMENTS > i
                    Box(Modifier.weight(1f).fillMaxHeight().background(ink.copy(alpha = if (lit) 1f else 0.12f)))
                }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .border(2.dp, ink, RoundedCornerShape(48.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val job = scope.launch {
                                val start = System.currentTimeMillis()
                                while (held < 1f) {
                                    held = ((System.currentTimeMillis() - start) / HOLD_MS.toFloat()).coerceAtMost(1f)
                                    delay(16)
                                }
                                send(WakeService.ACTION_DISMISS)
                            }
                            tryAwaitRelease()
                            if (held < 1f) { job.cancel(); held = 0f }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Text("HOLD STOP", color = ink, fontSize = 28.sp, letterSpacing = 4.sp) }
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
        const val SEGMENTS = 12
        const val HOLD_MS = 2_000L
    }
}
