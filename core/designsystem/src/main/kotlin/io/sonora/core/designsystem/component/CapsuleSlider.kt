package io.sonora.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

/**
 * iOS-style capsule slider without a thumb: a translucent track with a filled part that thickens while
 * dragged. Like iOS, it is relative: touching anywhere never jumps to the touch point; the value moves only by
 * the distance the finger travels, starting from the current value, and a plain tap changes nothing.
 * The displayed value stays continuous while dragging. A released drag commits through
 * [onValueChangeFinished]; a cancelled drag (e.g. a parent gesture stealing the pointer) commits nothing and
 * fires [onValueChangeCancelled] instead. Exactly one of the two ends every drag, so callers holding in-drag
 * overrides must drop them in both.
 *
 * Optional [markers] (fractions of the track, e.g. an A-B loop's points) are drawn as ticks across the track, with
 * [markerSpanColor] between the first two. A drag that starts within reach of a marker moves that marker instead of
 * the value, by the finger's travel like the value, through [onMarkerMove] and, on release, [onMarkerMoveFinished].
 */
@Composable
fun CapsuleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (Float) -> Unit = {},
    onValueChangeCancelled: () -> Unit = {},
    onDraggingChange: (Boolean) -> Unit = {},
    contentDescription: String? = null,
    stateDescription: String? = null,
    trackColor: Color = Color.White.copy(alpha = 0.3f),
    fillColor: Color = Color.White.copy(alpha = 0.85f),
    markers: List<Float> = emptyList(),
    onMarkerMove: (index: Int, value: Float) -> Unit = { _, _ -> },
    onMarkerMoveFinished: (index: Int, value: Float) -> Unit = { _, _ -> },
    markerColor: Color = Color.White,
    markerSpanColor: Color = Color.Transparent,
) {
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val onCancelled by rememberUpdatedState(onValueChangeCancelled)
    val onDragging by rememberUpdatedState(onDraggingChange)
    val current by rememberUpdatedState(value)
    val drag = remember {
        CapsuleDrag({ current }, { onChange(it) }, { onFinished(it) }, { onCancelled() }, { onDragging(it) })
    }
    val currentMarkers by rememberUpdatedState(markers)
    val onMarker by rememberUpdatedState(onMarkerMove)
    val onMarkerFinished by rememberUpdatedState(onMarkerMoveFinished)
    val markerDrag = remember {
        var grabbed = 0
        MarkerGrab(
            select = { grabbed = it },
            drag = CapsuleDrag(
                currentValue = { currentMarkers.getOrNull(grabbed) ?: 0f },
                onChange = { onMarker(grabbed, it) },
                onFinished = { onMarkerFinished(grabbed, it) },
                onCancelled = {},
                onDragging = {},
            ),
        )
    }
    val shown = (if (drag.dragging) drag.value else value).coerceIn(0f, 1f)
    val dragging = drag.dragging || markerDrag.drag.dragging
    val thickness by animateDpAsState(if (dragging) 12.dp else 6.dp, spring(), label = "capsuleThickness")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics(mergeDescendants = true) {
                contentDescription?.let { this.contentDescription = it }
                stateDescription?.let { this.stateDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f, steps = 0)
                setProgress { target ->
                    val v = target.coerceIn(0f, 1f)
                    onChange(v)
                    onFinished(v)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A plain tap never crosses the slop, so it changes nothing.
                    val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val travel = slop.position.x - down.position.x
                    val marker = markerUnder(down.position.x, width, currentMarkers, MarkerReach.toPx(), travel)
                    val target = if (marker == null) drag else markerDrag.drag.also { markerDrag.select(marker) }
                    // Measured from the touch-down point, so the travel inside the touch slop counts too.
                    target.start(travelBeforeStart = travel / width)
                    val released = try {
                        horizontalDrag(slop.id) { change ->
                            // Read the travel before consuming: a consumed change reports a zero positionChange().
                            val dx = change.positionChange().x
                            change.consume()
                            target.dragBy(dx / width)
                        }
                    } catch (e: CancellationException) {
                        target.cancel()
                        throw e
                    }
                    if (released) target.end() else target.cancel()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(thickness)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(trackColor, cornerRadius = r)
            drawRoundRect(fillColor, Offset.Zero, Size(size.width * shown, size.height), cornerRadius = r)
            if (markers.size >= 2) {
                val from = size.width * minOf(markers[0], markers[1]).coerceIn(0f, 1f)
                val to = size.width * maxOf(markers[0], markers[1]).coerceIn(0f, 1f)
                drawRect(markerSpanColor, Offset(from, 0f), Size(to - from, size.height))
            }
        }
        if (markers.isNotEmpty()) {
            Canvas(Modifier.fillMaxWidth().height(MarkerHeight)) {
                val w = MarkerWidth.toPx()
                for (m in markers) {
                    val x = (size.width * m.coerceIn(0f, 1f)).coerceIn(w / 2, size.width - w / 2)
                    drawRoundRect(markerColor, Offset(x - w / 2, 0f), Size(w, size.height), CornerRadius(w / 2, w / 2))
                }
            }
        }
    }
}

private val MarkerHeight = 16.dp
private val MarkerWidth = 3.dp

/** How far from a marker a drag may start and still move it. */
private val MarkerReach = 20.dp

/** Distances closer than this (px) count as a tie between markers. */
private const val MarkerTie = 1f

/** The marker a drag grabs and the drag that moves it. */
private class MarkerGrab(val select: (Int) -> Unit, val drag: CapsuleDrag)

/**
 * The index of the marker (a fraction of [width]) a drag starting at [x] grabs: the nearest within [reach] px, or none.
 * Markers on the same spot (a tie) go with the drag's direction [towards]: leftwards the lowest, rightwards the highest,
 * so two stacked markers can always be pulled apart.
 */
internal fun markerUnder(x: Float, width: Float, markers: List<Float>, reach: Float, towards: Float): Int? {
    val near = markers.indices.filter { abs(markers[it] * width - x) <= reach }
    if (near.isEmpty()) return null
    val nearest = near.minOf { abs(markers[it] * width - x) }
    val tied = near.filter { abs(markers[it] * width - x) - nearest < MarkerTie }
    return if (towards < 0) tied.minBy { markers[it] } else tied.maxBy { markers[it] }
}

/**
 * Drag lifecycle of [CapsuleSlider], as fractions of the track width. A drag starts from [currentValue] (not from
 * the touch point) and moves by the finger's travel only. A release commits the value through
 * [onFinished]; a cancel (e.g. a parent gesture taking over) only reports [onCancelled], so nothing is committed.
 * An end or cancel without a drag in progress is ignored, so no stale value is ever reported.
 */
internal class CapsuleDrag(
    private val currentValue: () -> Float,
    private val onChange: (Float) -> Unit,
    private val onFinished: (Float) -> Unit,
    private val onCancelled: () -> Unit,
    private val onDragging: (Boolean) -> Unit,
) {
    var dragging by mutableStateOf(false)
        private set
    var value by mutableFloatStateOf(0f)
        private set

    /**
     * Starts a drag from the current value plus [travelBeforeStart], the finger's travel (as a fraction of the track)
     * before the drag was recognised, so the value follows the finger 1:1 from the touch-down point.
     */
    fun start(travelBeforeStart: Float = 0f) {
        dragging = true
        onDragging(true)
        value = (currentValue().coerceIn(0f, 1f) + travelBeforeStart).coerceIn(0f, 1f)
        onChange(value)
    }

    fun dragBy(delta: Float) {
        value = (value + delta).coerceIn(0f, 1f)
        onChange(value)
    }

    fun end() = finish { onFinished(value) }

    fun cancel() = finish { onCancelled() }

    private inline fun finish(report: () -> Unit) {
        if (!dragging) return
        report()
        dragging = false
        onDragging(false)
    }
}
