package com.trepidity.good.wear.wake

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Black screen with the time during haptics (so it doesn't light the room), then a full-width
 * "swipe to dismiss" track once the sound starts. No snooze.
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
        var controls by remember { mutableStateOf(false) }

        LaunchedEffect(ui) {
            val current = ui ?: run { finish(); return@LaunchedEffect }
            while (true) {
                clock = now()
                controls = !Instant.now().isBefore(current.controlsAt)
                delay(1_000)
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(clock, fontSize = 40.sp, color = Color(0xFFFFB38A))
                if (controls) SwipeToDismiss()
            }
        }
    }

    @Composable
    private fun SwipeToDismiss() {
        var drag by remember { mutableFloatStateOf(0f) }
        val trackPx = 300f
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(56.dp)
                .background(Color(0xFF3A2418), RoundedCornerShape(28.dp))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (drag > trackPx * 0.6f) dismiss() else drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { _, delta -> drag = (drag + delta).coerceIn(0f, trackPx) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset { IntOffset(drag.roundToInt(), 0) }
                    .padding(4.dp)
                    .height(48.dp)
                    .background(Color(0xFFFFB38A), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Swipe →", color = Color.Black) }
        }
    }

    private fun dismiss() {
        startService(WakeStageService.actionIntent(this, WakeStageService.ACTION_DISMISS))
        finish()
    }

    private fun now(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm"))
}
