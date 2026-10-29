package io.sonora.feature.library.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.motion.LocalStackPush
import io.sonora.core.designsystem.motion.StackPush

/** Width of a row's trailing strip where a drag to push may start: the chevron box (20-44 dp from the edge) and slack. */
internal val PushStripWidth = 56.dp

/** A release at least this fast to the left pushes the page however little it was dragged (the pull's threshold). */
private val PushFlingVelocity = 1000.dp

/**
 * Tallest part of a row taken from the system's right-edge back gesture: the system grants 200 dp per edge, shared by
 * the library home's 7 rows (7 x 28 = 196 dp). Centred on the chevron it covers a finger landing up to 14 dp above or
 * below the glyph's centre; a down further out in the system zone is still Back.
 */
internal val ChevronExclusionHeight = 28.dp

/**
 * Drags [destination] in with the finger when a drag starts on the row's chevron and moves left (see [ChevronPush]),
 * through the enclosing stack's [LocalStackPush]; a tap and vertical scrolling are left alone. Nothing when
 * [destination] is `null` or the stack offers no push.
 */
@Composable
internal fun Modifier.chevronPush(destination: Any?): Modifier {
    if (destination == null) return this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Read lazily: once the page comes in, this screen is no longer at rest and loses the hook, but the drag that took
    // it must keep going. Keying the gesture on the hook would restart it, and cancel the drag, at the first move.
    val push by rememberUpdatedState(LocalStackPush.current)
    val density = LocalDensity.current
    val strip = with(density) { PushStripWidth.toPx() }
    val fling = with(density) { PushFlingVelocity.toPx() }
    return pointerInput(destination, rtl) { detectChevronPush({ push }, destination, strip, fling, rtl) }
}

/**
 * While [offered], takes from the system's right-edge back gesture the part of this row that the measured gesture inset
 * reaches, [ChevronExclusionHeight] tall around the chevron (see [ChevronPush.backExclusion]), so a drag aimed at the
 * chevron starts in the app instead of leaving it. Applied to the full-width row before its padding. Nothing with no
 * gesture inset (button navigation) or in a right-to-left layout.
 */
@Composable
internal fun Modifier.chevronBackExclusion(offered: Boolean): Modifier {
    if (!offered) return this
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inset = WindowInsets.systemGestures.getRight(density, LayoutDirection.Ltr).toFloat()
    if (rtl || inset <= 0f) return this
    val maxHeight = with(density) { ChevronExclusionHeight.toPx() }
    return systemGestureExclusion { row ->
        // The inset is measured from the window's right edge; only the part of it that reaches into this row counts.
        val root = row.findRootCoordinates()
        val rowRight = root.localPositionOf(row, Offset(row.size.width.toFloat(), 0f)).x
        val zone = inset - (root.size.width - rowRight)
        ChevronPush.backExclusion(row.size.width.toFloat(), row.size.height.toFloat(), zone, maxHeight, rtl) ?: Rect.Zero
    }
}

/**
 * Watched on the initial pass so a claimed drag is consumed before the list scrolls or the row's click fires. Moves are
 * measured as finger deltas, which this screen sliding aside under the finger does not change.
 */
private suspend fun PointerInputScope.detectChevronPush(
    hook: () -> StackPush?,
    destination: Any,
    strip: Float,
    flingVelocity: Float,
    rtl: Boolean,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val push = hook() ?: return@awaitEachGesture
        val width = size.width.toFloat()
        if (!ChevronPush.canStart(down.position.x, width, strip, rtl)) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val tracker = VelocityTracker()
        var fingerX = 0f
        var travelX = 0f
        var travelY = 0f
        var offset = ChevronPush.startOffset(down.position.x, width)
        var claimed = false
        var commit = false
        tracker.addPosition(down.uptimeMillis, Offset.Zero)
        // A claimed drag always ends in a release: interrupted (the screen leaves, the gesture is taken away), the page
        // slides back rather than staying half in with this screen blocked under it.
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                // Read the move before consuming it.
                val delta = change.positionChange()
                fingerX += delta.x
                tracker.addPosition(change.uptimeMillis, Offset(fingerX, 0f))
                if (!change.pressed) {
                    if (claimed) {
                        offset = ChevronPush.dragged(offset, delta.x) // the lift can still carry the last stretch
                        // On the initial pass nothing below has seen the lift yet: a consumed one is Compose's cancel
                        // (the system took the pointer back), which never pushes.
                        commit = ChevronPush.shouldPush(
                            offset, tracker.calculateVelocity().x, width, flingVelocity, cancelled = change.isConsumed,
                        )
                    }
                    break
                }
                if (!claimed) {
                    // Only an ancestor can have consumed it yet. The list is kept safe by the claim instead, which yields
                    // on vertical slop before the list's own drag starts: never relax it.
                    if (change.isConsumed) return@awaitEachGesture
                    travelX += delta.x
                    travelY += delta.y
                    when (ChevronPush.claim(travelX, travelY, slop)) {
                        ChevronPush.Claim.Undecided -> continue
                        ChevronPush.Claim.Yield -> return@awaitEachGesture
                        ChevronPush.Claim.Push -> {
                            claimed = true
                            offset = ChevronPush.dragged(offset, travelX)
                        }
                    }
                } else {
                    offset = ChevronPush.dragged(offset, delta.x)
                }
                change.consume()
                push.move(destination, ChevronPush.progress(offset, width))
            }
        } finally {
            if (claimed) push.release(destination, commit)
        }
    }
}
