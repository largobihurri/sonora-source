package io.sonora.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The translucent thumb of a segmented control: pure geometry and motion, kept apart from the composable so they can
 * be unit-tested. The thumb slides under the labels; its frame is read only in layout, never in composition.
 */

/** Where the selected thumb of a segmented control sits: its [offset] from the track's leading edge and its [width], in px. */
data class SegmentThumbFrame(val offset: Float, val width: Float) {
    companion object {
        /** No thumb: the choice is not among the options, or its segment is not measured yet. */
        val Hidden = SegmentThumbFrame(0f, 0f)
    }
}

/**
 * The thumb's frame over segment [index], given each segment's measured width in order (segments sit side by side from
 * the leading edge, so they may differ in width).
 */
fun segmentThumbFrame(index: Int, segmentWidths: List<Float>): SegmentThumbFrame {
    if (index !in segmentWidths.indices) return SegmentThumbFrame.Hidden
    var offset = 0f
    for (i in 0 until index) offset += segmentWidths[i]
    return SegmentThumbFrame(offset, segmentWidths[index])
}

/** How the thumb reaches a new frame. */
enum class SegmentThumbMotion { Jump, Spring }

/**
 * The thumb springs only from one placed choice to another; it jumps under reduced motion ("Remove animations"), at its
 * first placement, and when only the layout changed (a resize keeps the same choice).
 */
fun segmentThumbMotion(reducedMotion: Boolean, placed: Boolean, indexChanged: Boolean): SegmentThumbMotion =
    if (!reducedMotion && placed && indexChanged) SegmentThumbMotion.Spring else SegmentThumbMotion.Jump

/** A slide with a slight overshoot, as the iOS 26 segmented thumb does. */
private val ThumbSpring = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

/** The selected thumb's animated frame; read [offset] and [width] only in layout or draw. */
@Stable
class SegmentThumbState {
    internal val segmentWidths = mutableStateListOf<Float>()
    private val offsetAnim = Animatable(0f)
    private val widthAnim = Animatable(0f)
    private var placedIndex = -1

    val offset: Float get() = offsetAnim.value
    val width: Float get() = widthAnim.value

    /** Records the measured [width] of segment [index]. */
    fun segmentMeasured(index: Int, width: Float) {
        while (segmentWidths.size <= index) segmentWidths.add(0f)
        if (segmentWidths[index] != width) segmentWidths[index] = width
    }

    /**
     * Moves the thumb over segment [index] at [frame]: a spring between choices, a jump under [reducedMotion]. A hidden
     * [frame] is no choice: the thumb goes at once and is placed nowhere, so whichever choice comes next is a first
     * placement (the one it left included).
     */
    suspend fun moveTo(index: Int, frame: SegmentThumbFrame, reducedMotion: Boolean) {
        val shown = frame.width > 0f
        val placed = placedIndex >= 0 && shown
        val motion = segmentThumbMotion(reducedMotion, placed, indexChanged = index != placedIndex)
        placedIndex = if (shown) index else -1
        when (motion) {
            SegmentThumbMotion.Jump -> {
                offsetAnim.snapTo(frame.offset)
                widthAnim.snapTo(frame.width)
            }
            SegmentThumbMotion.Spring -> coroutineScope {
                launch { offsetAnim.animateTo(frame.offset, ThumbSpring) }
                launch { widthAnim.animateTo(frame.width, ThumbSpring) }
            }
        }
    }
}

/**
 * Sizes and places the thumb inside the full track (a `matchParentSize` box) from [state], read here in layout so the
 * slide re-lays out the thumb without recomposing. Placement is relative, so the thumb mirrors in right-to-left.
 */
internal fun Modifier.segmentThumb(state: SegmentThumbState): Modifier = layout { measurable, constraints ->
    val width = state.width.roundToInt().coerceIn(0, constraints.maxWidth)
    val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeRelative(state.offset.roundToInt(), 0)
    }
}
