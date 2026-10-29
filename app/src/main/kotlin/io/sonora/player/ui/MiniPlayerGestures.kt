package io.sonora.player.ui

import io.sonora.core.domain.model.RepeatMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** The axis a mini player drag moves along: horizontal changes track, vertical drives the Now Playing sheet. */
enum class DragAxis { Horizontal, Vertical }

/**
 * Picks a drag's axis once the finger has moved more than [slop] from where it went down: the dominant component of
 * the total movement wins (a tie goes to the sheet), and the choice holds for the rest of the gesture. [totalX] and
 * [totalY] keep the movement up to the lock, so the dragged content can start exactly under the finger.
 */
class AxisLock(private val slop: Float) {
    var totalX = 0f
        private set
    var totalY = 0f
        private set
    private var locked: DragAxis? = null

    /** Adds a movement and returns the locked axis, or `null` while still within the slop. */
    fun add(deltaX: Float, deltaY: Float): DragAxis? {
        locked?.let { return it }
        totalX += deltaX
        totalY += deltaY
        if (hypot(totalX, totalY) <= slop) return null
        return (if (abs(totalX) > abs(totalY)) DragAxis.Horizontal else DragAxis.Vertical).also { locked = it }
    }
}

/** UIKit's rubber band: [overshoot] px past a limit shows as less and less movement, never reaching [dimension]. */
fun rubberBand(overshoot: Float, dimension: Float, coefficient: Float = 0.55f): Float =
    (1f - 1f / (overshoot * coefficient / dimension + 1f)) * dimension

/**
 * Where the mini player's content sits for a finger [rawPx] away from its start: one to one (up to the full [widthPx])
 * towards a track that exists, rubber-banded towards one that doesn't. Left (negative) is next, right is previous.
 */
fun swipeOffset(rawPx: Float, widthPx: Float, canNext: Boolean, canPrevious: Boolean): Float {
    val allowed = if (rawPx < 0f) canNext else canPrevious
    return if (allowed) rawPx.coerceIn(-widthPx, widthPx) else sign(rawPx) * rubberBand(abs(rawPx), widthPx)
}

enum class SwipeOutcome { Next, Previous, SpringBack }

/** Share of the content width a slow swipe must travel to change track. */
const val SWIPE_COMMIT_FRACTION = 0.3f

/**
 * What a released horizontal swipe does: a fling faster than [flingPx] (px/s) in the direction of the swipe commits it,
 * one against the swipe cancels it; otherwise it commits past [SWIPE_COMMIT_FRACTION] of [widthPx]. A direction without
 * a track springs back.
 */
fun swipeOutcome(
    offsetPx: Float,
    velocityPx: Float,
    widthPx: Float,
    flingPx: Float,
    canNext: Boolean,
    canPrevious: Boolean,
): SwipeOutcome {
    val direction = when {
        abs(velocityPx) >= flingPx -> if (velocityPx * offsetPx >= 0f) sign(velocityPx) else 0f
        abs(offsetPx) >= widthPx * SWIPE_COMMIT_FRACTION -> sign(offsetPx)
        else -> 0f
    }
    return when {
        direction < 0f && canNext -> SwipeOutcome.Next
        direction > 0f && canPrevious -> SwipeOutcome.Previous
        else -> SwipeOutcome.SpringBack
    }
}

/** Which tracks the mini player can swipe to. */
data class MiniPlayerSkips(val canNext: Boolean, val canPrevious: Boolean)

/**
 * Next/previous exist inside the queue, or around it with Repeat All (Repeat One skips like Off, as Media3 does). A
 * committed previous swipe still follows skipToPrevious, which restarts a track more than 3 s in.
 */
fun miniPlayerSkips(queueSize: Int, currentIndex: Int, repeatMode: RepeatMode): MiniPlayerSkips {
    if (queueSize <= 0 || currentIndex !in 0 until queueSize) return MiniPlayerSkips(canNext = false, canPrevious = false)
    val wraps = repeatMode == RepeatMode.ALL && queueSize > 1
    return MiniPlayerSkips(canNext = wraps || currentIndex < queueSize - 1, canPrevious = wraps || currentIndex > 0)
}
