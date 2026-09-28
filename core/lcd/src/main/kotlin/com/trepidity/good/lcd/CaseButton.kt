package com.trepidity.good.lcd

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HOLD_MS = 2_000L
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_EVERY_MS = 80L

/**
 * A pill-shaped case button with its [label] in small caps underneath; the whole column (≥ 56 dp tall) is the
 * touch target. Every press gives a crisp haptic tick.
 *
 * - Plain: a tap calls [onClick].
 * - With [onLongClick]: holding 2 s calls it, reporting 0..1 through [onHoldProgress] (with a tick every tenth)
 *   and resetting to 0 on early release, which otherwise does nothing; a quick tap still calls [onClick].
 * - With [repeatOnHold]: holding calls [onClick] after 400 ms and then every 80 ms (▲/▼ "hold = fast").
 *
 * TalkBack gets [contentDescription] plus click and long-click actions.
 */
@Composable
fun CaseButton(
    label: String,
    palette: LcdPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onHoldProgress: ((Float) -> Unit)? = null,
    repeatOnHold: Boolean = false,
    contentDescription: String = label,
    faceHeight: Dp = 28.dp,
) {
    val view = LocalView.current
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val holdProgress by rememberUpdatedState(onHoldProgress)
    val hasLong = onLongClick != null
    var pressed by remember { mutableStateOf(false) }

    Column(
        modifier
            .heightIn(min = 56.dp)
            .widthIn(min = 64.dp)
            .clearAndSetSemantics {
                role = Role.Button
                this.contentDescription = contentDescription
                this.onClick { click(); true }
                if (hasLong) this.onLongClick { longClick?.invoke(); true }
            }
            .pointerInput(view, hasLong, repeatOnHold) {
                coroutineScope {
                    val scope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downAt = SystemClock.uptimeMillis()
                        pressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        var held = false
                        val job: Job? = when {
                            hasLong -> scope.launch {
                                var ticks = 0
                                while (true) {
                                    val p = ((SystemClock.uptimeMillis() - downAt) / HOLD_MS.toFloat()).coerceAtMost(1f)
                                    holdProgress?.invoke(p)
                                    if (p >= 1f) {
                                        held = true
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        longClick?.invoke()
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
                            repeatOnHold -> scope.launch {
                                delay(REPEAT_DELAY_MS)
                                held = true
                                while (true) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    click()
                                    delay(REPEAT_EVERY_MS)
                                }
                            }
                            else -> null
                        }
                        val up = waitForUpOrCancellation()
                        job?.cancel()
                        pressed = false
                        if (hasLong && !held) holdProgress?.invoke(0f)
                        // Judge tap vs. hold by the events' own timestamps: on a slow frame, processing the release
                        // late must not turn a tap into an abandoned hold.
                        val quick = up == null || up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                        if (up != null && !held && (!hasLong || quick)) {
                            up.consume()
                            click()
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val pill = RoundedCornerShape(percent = 50)
        val face = if (pressed) 0.06f else 0.14f
        Box(
            Modifier
                .fillMaxWidth()
                .height(faceHeight)
                .offset(y = if (pressed) 1.dp else 0.dp)
                .clip(pill)
                .background(
                    Brush.verticalGradient(
                        listOf(lerp(palette.caseColor, Color.White, face), lerp(palette.caseColor, Color.Black, 0.35f)),
                    ),
                )
                .border(1.dp, Color.Black.copy(alpha = 0.6f), pill),
        )
        Spacer(Modifier.height(5.dp))
        BasicText(
            text = label.uppercase(),
            style = TextStyle(
                color = palette.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
