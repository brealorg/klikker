package no.enaasen.tvremote.ui.remote

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch
import no.enaasen.tvremote.R

enum class SwipeDirection { Up, Down, Left, Right }

/** Drag distance before the first step, so a sloppy tap doesn't move. */
private val FirstStepDistance = 24.dp

/** Further drag distance per additional step, by sensitivity 1–5. */
private val StepDistances = listOf(300.dp, 260.dp, 220.dp, 150.dp, 80.dp)

/** Release speed above which a swipe sends extra steps, when enabled. */
private val FlingThreshold = 1_500.dp

/** Each additional this-much speed above the threshold adds a step. */
private val FlingStepSpeed = 1_000.dp

private const val MAX_FLING_STEPS = 3

/** Finger drift smaller than this still counts as resting. */
private val RestSlop = 16.dp

/** How long the finger must rest after a step before repeating starts. */
private const val HOLD_REPEAT_DELAY_MS = 250L

/** Repeats start slowly, so they're easy to stop precisely, then speed up to [HOLD_REPEAT_MIN_INTERVAL_MS]. */
private const val HOLD_REPEAT_FIRST_INTERVAL_MS = 320L
private const val HOLD_REPEAT_MIN_INTERVAL_MS = 140L
private const val HOLD_REPEAT_ACCELERATION = 0.85

/** Interval multipliers for hold speed calm, medium (the tuned default) and fast. */
private val HoldRepeatIntervalScales = listOf(1.4, 1.0, 0.7)

private const val NO_REPEAT = Long.MAX_VALUE

/**
 * A touchpad that turns gestures into D-pad input: a swipe steps once in its
 * dominant direction and keeps stepping as the finger travels further (how
 * far depends on [sensitivity]). Resting the finger after a step keeps
 * stepping that way when [holdToRepeat] is on. A tap selects and a long press
 * holds select.
 * Every step is confirmed with a haptic tick.
 */
@Composable
fun SwipePad(
    enabled: Boolean,
    sensitivity: Int,
    flingEnabled: Boolean,
    holdToRepeat: Boolean,
    holdRepeatSpeed: Int,
    onDirection: (SwipeDirection) -> Unit,
    onSelect: () -> Unit,
    onSelectHeld: (held: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pulse = remember { Animatable(1f) }
    var touch by remember { mutableStateOf<Offset?>(null) }

    val currentOnDirection by rememberUpdatedState(onDirection)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnSelectHeld by rememberUpdatedState(onSelectHeld)

    fun pulseIndicator() {
        scope.launch {
            pulse.snapTo(1.3f)
            pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    fun step(direction: SwipeDirection) {
        currentOnDirection(direction)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        pulseIndicator()
    }

    fun repeatStep(direction: SwipeDirection) {
        currentOnDirection(direction)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        pulseIndicator()
    }

    val indicatorColor = MaterialTheme.colorScheme.primary
    val labels = rememberDirectionLabels()
    val selectLabel = stringResource(R.string.select)
    val padLabel = stringResource(R.string.swipe_pad)
    val disabledLabel = stringResource(R.string.swipe_pad_state_disabled)

    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = padLabel
                    if (enabled) {
                        onClick(label = selectLabel) { currentOnSelect(); true }
                        customActions = SwipeDirection.entries.map { direction ->
                            CustomAccessibilityAction(labels[direction]) { currentOnDirection(direction); true }
                        }
                    } else {
                        disabled()
                        stateDescription = disabledLabel
                    }
                }
                .pointerInput(enabled, sensitivity, flingEnabled, holdToRepeat, holdRepeatSpeed) {
                    if (!enabled) return@pointerInput
                    val firstStepPx = FirstStepDistance.toPx()
                    val restSlopPx = RestSlop.toPx()
                    val intervalScale = HoldRepeatIntervalScales[(holdRepeatSpeed - 1).coerceIn(HoldRepeatIntervalScales.indices)]
                    val firstRepeatInterval = (HOLD_REPEAT_FIRST_INTERVAL_MS * intervalScale).toLong()
                    val minRepeatInterval = (HOLD_REPEAT_MIN_INTERVAL_MS * intervalScale).toLong()
                    val stepPx = StepDistances[(sensitivity - 1).coerceIn(StepDistances.indices)].toPx()
                    val flingThresholdPx = FlingThreshold.toPx()
                    val flingStepPx = FlingStepSpeed.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touch = down.position
                        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }

                        val gesture = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            awaitTapOrDrag(down, viewConfiguration.touchSlop)
                        }
                        when (gesture) {
                            null -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentOnSelectHeld(true)
                                waitForUpOrCancellation()
                                currentOnSelectHeld(false)
                            }
                            Gesture.Tap -> {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                currentOnSelect()
                            }
                            Gesture.Cancel -> Unit
                            is Gesture.Drag -> {
                                var pending = gesture.displacement
                                var threshold = firstStepPx
                                var lastDirection: SwipeDirection? = null
                                var restAnchor = down.position + gesture.displacement
                                var nextRepeatAt = NO_REPEAT
                                var repeatInterval = firstRepeatInterval

                                fun emitSteps(now: Long) {
                                    while (true) {
                                        val direction = pending.dominantDirection(threshold) ?: break
                                        step(direction)
                                        pending = pending.consumeStep(direction, threshold)
                                        threshold = stepPx
                                        lastDirection = direction
                                        if (holdToRepeat) nextRepeatAt = now + HOLD_REPEAT_DELAY_MS
                                        repeatInterval = firstRepeatInterval
                                    }
                                }

                                emitSteps(SystemClock.uptimeMillis())
                                while (true) {
                                    val event = if (nextRepeatAt == NO_REPEAT) {
                                        awaitPointerEvent()
                                    } else {
                                        withTimeoutOrNull((nextRepeatAt - SystemClock.uptimeMillis()).coerceAtLeast(1)) {
                                            awaitPointerEvent()
                                        }
                                    }
                                    if (event == null) {
                                        // The finger is resting after a step: keep going, like holding a D-pad key.
                                        lastDirection?.let { repeatStep(it) }
                                        nextRepeatAt = SystemClock.uptimeMillis() + repeatInterval
                                        repeatInterval = (repeatInterval * HOLD_REPEAT_ACCELERATION).toLong()
                                            .coerceAtLeast(minRepeatInterval)
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    // Count the movement first: in a quick flick, most of the distance
                                    // arrives in the same event as the finger lifting.
                                    velocity.addPosition(change.uptimeMillis, change.position)
                                    pending += change.positionChange()
                                    touch = change.position
                                    change.consume()
                                    if ((change.position - restAnchor).getDistance() > restSlopPx) {
                                        // Still moving, so not resting yet.
                                        restAnchor = change.position
                                        if (nextRepeatAt != NO_REPEAT) {
                                            nextRepeatAt = change.uptimeMillis + HOLD_REPEAT_DELAY_MS
                                            repeatInterval = firstRepeatInterval
                                        }
                                    }
                                    emitSteps(change.uptimeMillis)
                                    if (!change.pressed) {
                                        if (flingEnabled) {
                                            val v = velocity.calculateVelocity()
                                            val (speed, direction) = if (abs(v.x) >= abs(v.y)) {
                                                abs(v.x) to if (v.x > 0) SwipeDirection.Right else SwipeDirection.Left
                                            } else {
                                                abs(v.y) to if (v.y > 0) SwipeDirection.Down else SwipeDirection.Up
                                            }
                                            if (speed > flingThresholdPx) {
                                                val extra = min(MAX_FLING_STEPS, ((speed - flingThresholdPx) / flingStepPx).toInt() + 1)
                                                repeat(extra) { step(direction) }
                                            }
                                        }
                                        break
                                    }
                                }
                            }
                        }
                        touch = null
                    }
                }
                .drawBehind {
                    val position = touch ?: return@drawBehind
                    drawCircle(
                        color = indicatorColor.copy(alpha = 0.16f),
                        radius = 40.dp.toPx() * pulse.value,
                        center = position,
                    )
                    drawCircle(
                        color = indicatorColor.copy(alpha = 0.32f),
                        radius = 12.dp.toPx(),
                        center = position,
                    )
                },
        ) {
            if (touch == null && !enabled) {
                Text(
                    text = stringResource(R.string.swipe_pad_state_disabled),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private sealed interface Gesture {
    data object Tap : Gesture
    data object Cancel : Gesture
    data class Drag(val displacement: Offset) : Gesture
}

private suspend fun AwaitPointerEventScope.awaitTapOrDrag(down: PointerInputChange, touchSlop: Float): Gesture {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return Gesture.Cancel
        if (!change.pressed) return if (change.isConsumed) Gesture.Cancel else Gesture.Tap
        val displacement = change.position - down.position
        if (displacement.getDistance() > touchSlop) {
            change.consume()
            return Gesture.Drag(displacement)
        }
    }
}

/** Removes one step along [direction] and bleeds off cross-axis drift so a slanted swipe stays straight. */
private fun Offset.consumeStep(direction: SwipeDirection, distance: Float): Offset = when (direction) {
    SwipeDirection.Left -> Offset(x + distance, y / 2)
    SwipeDirection.Right -> Offset(x - distance, y / 2)
    SwipeDirection.Up -> Offset(x / 2, y + distance)
    SwipeDirection.Down -> Offset(x / 2, y - distance)
}

private fun Offset.dominantDirection(stepPx: Float): SwipeDirection? = when {
    abs(x) >= stepPx && abs(x) >= abs(y) -> if (x > 0) SwipeDirection.Right else SwipeDirection.Left
    abs(y) >= stepPx -> if (y > 0) SwipeDirection.Down else SwipeDirection.Up
    else -> null
}

private class DirectionLabels(private val labels: Map<SwipeDirection, String>) {
    operator fun get(direction: SwipeDirection) = labels.getValue(direction)
}

@Composable
private fun rememberDirectionLabels() = DirectionLabels(
    mapOf(
        SwipeDirection.Up to stringResource(R.string.direction_up),
        SwipeDirection.Down to stringResource(R.string.direction_down),
        SwipeDirection.Left to stringResource(R.string.direction_left),
        SwipeDirection.Right to stringResource(R.string.direction_right),
    ),
)
