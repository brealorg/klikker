package no.enaasen.tvremote.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import no.enaasen.tvremote.R

/** What the volume ring shows, from the receiver when it is connected and otherwise the TV. */
internal data class VolumeDisplay(val level: String?, val fraction: Float?, val isMuted: Boolean)

private val RingWidth = 14.dp
private val RingTouchWidth = 44.dp
private val RingGap = 10.dp
private val NudgeDistance = 10.dp

// The ring runs clockwise from bottom left to bottom right, like an amplifier's knob.
private const val RING_START_DEGREES = 135f
private const val RING_SWEEP_DEGREES = 270f
private const val DEGREES_PER_STEP = 15f

/**
 * The remote's centrepiece: a round swipe pad inside a volume ring. Dragging
 * around the ring turns the volume like a physical knob, one step (and one
 * haptic tick) per [DEGREES_PER_STEP], wherever the drag starts, so the volume
 * never jumps. The pad's scalloped edge turns with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VolumeKnob(
    volume: VolumeDisplay?,
    padEnabled: Boolean,
    onVolumeStep: (up: Boolean) -> Unit,
    pad: @Composable (Modifier, onNudge: (SwipeDirection) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnVolumeStep by rememberUpdatedState(onVolumeStep)
    val volumeEnabled = volume != null
    var knobRotation by remember { mutableFloatStateOf(0f) }
    var turning by remember { mutableStateOf(false) }
    val waveAmplitude by animateFloatAsState(
        targetValue = if (turning) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "wave",
    )
    val nudge = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    val nudgePx = with(LocalDensity.current) { NudgeDistance.toPx() }

    val volumeLabel = stringResource(R.string.volume)
    val upLabel = stringResource(R.string.volume_up)
    val downLabel = stringResource(R.string.volume_down)
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight)
        val padDiameter = diameter - (RingTouchWidth + RingGap) * 2
        Box(
            modifier = Modifier
                .size(diameter)
                .semantics {
                    contentDescription = volumeLabel
                    volume?.level?.let { stateDescription = it }
                    volume?.fraction?.let { progressBarRangeInfo = ProgressBarRangeInfo(it, 0f..1f) }
                    if (volumeEnabled) {
                        customActions = listOf(
                            CustomAccessibilityAction(upLabel) { currentOnVolumeStep(true); true },
                            CustomAccessibilityAction(downLabel) { currentOnVolumeStep(false); true },
                        )
                    }
                }
                .pointerInput(volumeEnabled) {
                    if (!volumeEnabled) return@pointerInput
                    val padRadius = padDiameter.toPx() / 2
                    // Runs before the pad sees the touch, so a touch on the ring never moves the cursor.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        if ((down.position - centre).getDistance() < padRadius) return@awaitEachGesture
                        down.consume()
                        turning = true
                        var lastAngle = angleDegrees(down.position, centre)
                        var unspent = 0f
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            val angle = angleDegrees(change.position, centre)
                            val delta = ((angle - lastAngle + 540f) % 360f) - 180f
                            lastAngle = angle
                            knobRotation += delta
                            unspent += delta
                            // Clockwise is up, as on an amplifier.
                            while (unspent >= DEGREES_PER_STEP) {
                                unspent -= DEGREES_PER_STEP
                                currentOnVolumeStep(true)
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            while (unspent <= -DEGREES_PER_STEP) {
                                unspent += DEGREES_PER_STEP
                                currentOnVolumeStep(false)
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            change.consume()
                        }
                        turning = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = RingWidth.toPx()
                val radius = size.minDimension / 2 - RingTouchWidth.toPx() / 2
                drawRingTrack(radius, stroke, if (volumeEnabled) colors.secondaryContainer else colors.surfaceContainerHigh)
                val fraction = volume?.fraction
                if (fraction != null) {
                    val activeColor = if (volume.isMuted) colors.outline else colors.primary
                    drawWavyArc(radius, stroke, fraction, waveAmplitude * stroke * 0.35f, activeColor)
                    val end = Math.toRadians((RING_START_DEGREES + RING_SWEEP_DEGREES * fraction).toDouble())
                    drawCircle(
                        color = activeColor,
                        radius = stroke * (0.9f + 0.4f * waveAmplitude),
                        center = center + Offset(cos(end).toFloat() * radius, sin(end).toFloat() * radius),
                    )
                }
            }

            // The pad's scalloped edge turns with the ring; the pad itself stays put.
            val cookie = MaterialShapes.Cookie12Sided.toShape()
            Box(
                modifier = Modifier
                    .size(padDiameter)
                    .offset { IntOffset(nudge.value.x.roundToInt(), nudge.value.y.roundToInt()) },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = knobRotation }
                        .clip(cookie)
                        .background(if (padEnabled) colors.surfaceContainerHigh else colors.surfaceContainer),
                )
                pad(Modifier.fillMaxSize().padding(8.dp)) { direction ->
                    // A small push toward the swipe, springing back, so each step is felt in the UI too.
                    scope.launch {
                        val distance = nudgePx
                        val target = when (direction) {
                            SwipeDirection.Up -> Offset(0f, -distance)
                            SwipeDirection.Down -> Offset(0f, distance)
                            SwipeDirection.Left -> Offset(-distance, 0f)
                            SwipeDirection.Right -> Offset(distance, 0f)
                        }
                        nudge.animateTo(target, spring(stiffness = Spring.StiffnessHigh))
                        nudge.animateTo(Offset.Zero, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            }

            volume?.level?.let { level ->
                // In the gap at the bottom of the ring.
                Text(
                    text = level,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (volume.isMuted) colors.outline else colors.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                )
            }
        }
    }
}

private fun angleDegrees(point: Offset, centre: Offset): Float =
    Math.toDegrees(atan2((point.y - centre.y).toDouble(), (point.x - centre.x).toDouble())).toFloat()

private fun DrawScope.drawRingTrack(radius: Float, stroke: Float, color: Color) {
    drawArc(
        color = color,
        startAngle = RING_START_DEGREES,
        sweepAngle = RING_SWEEP_DEGREES,
        useCenter = false,
        topLeft = center - Offset(radius, radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

/** The level along the ring as a line that ripples while the knob is being turned. */
private fun DrawScope.drawWavyArc(radius: Float, stroke: Float, fraction: Float, amplitude: Float, color: Color) {
    val sweep = RING_SWEEP_DEGREES * fraction.coerceIn(0f, 1f)
    if (sweep <= 0f) return
    val path = Path()
    val steps = (sweep / 1.5f).toInt().coerceAtLeast(1)
    for (i in 0..steps) {
        val degrees = RING_START_DEGREES + sweep * i / steps
        val r = radius + amplitude * sin(Math.toRadians(degrees * 12.0)).toFloat()
        val radians = Math.toRadians(degrees.toDouble())
        val point = center + Offset(cos(radians).toFloat() * r, sin(radians).toFloat() * r)
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
}
