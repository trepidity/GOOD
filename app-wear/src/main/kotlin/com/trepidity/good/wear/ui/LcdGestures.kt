package com.trepidity.good.wear.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val HOLD_MS = 2_000L
private const val HOLD_SHOW_MS = 250L

/**
 * The watch face's buttons, all on the glass: a quick tap reports where it landed ([onTap]); a sideways swipe
 * reports its direction ([onSwipe]: -1 = leftward, +1 = rightward); holding still for 2 s calls [onSet], with
 * progress through [onHold] (shown after a short delay so taps don't flash the ring) and a tick every tenth.
 * Releasing early does nothing. Without [holdEnabled] a hold is ignored.
 */
@Composable
fun Modifier.lcdGestures(
    holdEnabled: Boolean,
    onTap: (Offset, IntSize) -> Unit,
    onSwipe: (Int) -> Unit,
    onHold: (Float) -> Unit,
    onSet: () -> Unit,
): Modifier {
    val view: View = LocalView.current
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val hold by rememberUpdatedState(onHold)
    val set by rememberUpdatedState(onSet)
    return pointerInput(holdEnabled) {
        val swipeMin = 40.dp.toPx()
        coroutineScope {
            val scope = this
            awaitEachGesture {
                val down = awaitFirstDown()
                val downAt = SystemClock.uptimeMillis()
                var fired = false
                val job: Job? = if (!holdEnabled) null else scope.launch {
                    delay(HOLD_SHOW_MS)
                    var ticks = 0
                    while (true) {
                        val p = ((SystemClock.uptimeMillis() - downAt) / HOLD_MS.toFloat()).coerceAtMost(1f)
                        hold(p)
                        if (p >= 1f) {
                            fired = true
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            set()
                            break
                        }
                        val tick = (p * 10).toInt()
                        if (tick > ticks) {
                            ticks = tick
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        delay(16)
                    }
                }
                var dragged = false
                var last = down.position
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    last = change.position
                    if (!dragged && (last - down.position).getDistance() > viewConfiguration.touchSlop) {
                        dragged = true
                        job?.cancel()
                        if (!fired) hold(0f)
                    }
                    if (dragged) change.consume()
                }
                job?.cancel()
                if (fired) {
                    hold(0f)
                    return@awaitEachGesture
                }
                hold(0f)
                val d = last - down.position
                when {
                    dragged && abs(d.x) > swipeMin && abs(d.x) > abs(d.y) -> swipe(if (d.x < 0) -1 else 1)
                    !dragged && SystemClock.uptimeMillis() - downAt < viewConfiguration.longPressTimeoutMillis -> {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        tap(down.position, size)
                    }
                }
            }
        }
    }
}
