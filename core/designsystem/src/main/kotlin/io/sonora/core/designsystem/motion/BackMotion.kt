package io.sonora.core.designsystem.motion

import android.content.Context
import android.provider.Settings
import androidx.activity.BackEventCompat
import kotlin.math.roundToInt

/** Share of the width the previous screen sits under the top one (iOS parallax). */
const val STACK_PARALLAX = 0.3f

/** Dim over the previous screen while it is covered. */
const val STACK_MAX_SCRIM = 0.12f

/** Full push/pop duration; a committed gesture finishes in the share it has left. */
const val STACK_DURATION_MILLIS = 350

/** How much the Now Playing sheet shrinks and drops (share of its height) at full back progress. */
const val SHEET_MAX_SHRINK = 0.1f
const val SHEET_MAX_OFFSET = 0.06f

/** Screen edge a back swipe started from; button presses report [None], a page pulled down by its header [Top]. */
enum class SwipeEdge { Left, Right, None, Top }

fun swipeEdgeOf(backEventEdge: Int): SwipeEdge = when (backEventEdge) {
    BackEventCompat.EDGE_LEFT -> SwipeEdge.Left
    BackEventCompat.EDGE_RIGHT -> SwipeEdge.Right
    else -> SwipeEdge.None
}

/**
 * Offsets of the top and previous screens, a share of the width, or of the height when [vertical], and the dim over
 * the previous screen.
 */
data class StackFrame(val topOffset: Float, val underOffset: Float, val scrimAlpha: Float, val vertical: Boolean = false)

/**
 * Pop/push frame at [progress] (0 = top screen in place, 1 = top screen gone). The top screen moves away from
 * the edge the swipe started at; a right-edge swipe mirrors the motion. Pushes use the reverse of a left pop. A pull
 * from the [SwipeEdge.Top] moves the top screen straight down over the previous one, which does not move.
 */
fun stackFrame(progress: Float, edge: SwipeEdge): StackFrame {
    val p = progress.coerceIn(0f, 1f)
    if (edge == SwipeEdge.Top) return StackFrame(topOffset = p, underOffset = 0f, scrimAlpha = STACK_MAX_SCRIM * (1f - p), vertical = true)
    val direction = if (edge == SwipeEdge.Right) -1f else 1f
    // `+ 0f` turns a negative zero into 0f so resting frames compare equal.
    return StackFrame(
        topOffset = direction * p + 0f,
        underOffset = -direction * STACK_PARALLAX * (1f - p) + 0f,
        scrimAlpha = STACK_MAX_SCRIM * (1f - p),
    )
}

/** Now Playing sheet transform at back [progress]: scale, downward offset (share of height), corner rounding. */
data class SheetFrame(val scale: Float, val offsetFraction: Float, val cornerFraction: Float)

fun sheetFrame(progress: Float): SheetFrame {
    val p = progress.coerceIn(0f, 1f)
    return SheetFrame(scale = 1f - SHEET_MAX_SHRINK * p, offsetFraction = SHEET_MAX_OFFSET * p, cornerFraction = p)
}

/** Time left to finish a pop already at [progress], so a released gesture keeps the same pace. */
fun remainingDurationMillis(progress: Float, fullMillis: Int = STACK_DURATION_MILLIS): Int =
    (fullMillis * (1f - progress.coerceIn(0f, 1f))).roundToInt()

/** "Remove animations" (animator duration scale 0) turns gesture-following motion off. */
fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** Reads the current system setting; cheap enough to call at the start of each transition. */
fun Context.prefersReducedMotion(): Boolean =
    isReducedMotion(Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
