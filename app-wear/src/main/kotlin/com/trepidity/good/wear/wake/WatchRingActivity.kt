package com.trepidity.good.wear.wake

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Black screen with the time during haptics (so it doesn't light the room). Once the sound starts,
 * press and hold anywhere for 2 s: 60 segments fill around the round edge like a seconds track. No snooze.
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
        var armed by remember { mutableStateOf(false) }
        var held by remember { mutableFloatStateOf(0f) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(ui) {
            val current = ui ?: run { finish(); return@LaunchedEffect }
            while (true) {
                clock = now()
                armed = !Instant.now().isBefore(current.controlsAt)
                delay(1_000)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(armed) {
                    if (!armed) return@pointerInput
                    detectTapGestures(onPress = {
                        val job = scope.launch {
                            val start = System.currentTimeMillis()
                            while (held < 1f) {
                                held = ((System.currentTimeMillis() - start) / HOLD_MS.toFloat()).coerceAtMost(1f)
                                delay(16)
                            }
                            dismiss()
                        }
                        tryAwaitRelease()
                        if (held < 1f) { job.cancel(); held = 0f }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            if (armed) SegmentRing(held)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(clock, fontSize = 44.sp, color = AMBER)
                if (armed) Text("HOLD TO STOP", fontSize = 12.sp, color = AMBER.copy(alpha = 0.7f), letterSpacing = 2.sp)
            }
        }
    }

    /** 60 tick marks around the round display; lit ones show hold progress. */
    @Composable
    private fun SegmentRing(progress: Float) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            for (i in 0 until 60) {
                val a = (i / 60.0) * 2 * PI - PI / 2
                val outer = Offset(c.x + (r - 4f) * cos(a).toFloat(), c.y + (r - 4f) * sin(a).toFloat())
                val inner = Offset(c.x + (r - 18f) * cos(a).toFloat(), c.y + (r - 18f) * sin(a).toFloat())
                val lit = progress * 60 > i
                drawLine(AMBER.copy(alpha = if (lit) 1f else 0.15f), inner, outer, strokeWidth = 5f, cap = StrokeCap.Round)
            }
        }
    }

    private fun dismiss() {
        startService(WakeStageService.actionIntent(this, WakeStageService.ACTION_DISMISS))
        finish()
    }

    private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm"))

    private companion object {
        val AMBER = Color(0xFFFFB38A)
        const val HOLD_MS = 2_000L
    }
}
