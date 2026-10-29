package io.sonora.core.designsystem.component

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** How close to a list's edge a held row starts scrolling it. */
private val EdgeZone = 60.dp

/** How fast a list scrolls under a row held right at (or past) its edge, in dp per second. */
private const val MAX_SPEED_DP_PER_SECOND = 1400f

/** The longest frame one auto-scroll step covers (two 60 Hz frames), so a stalled frame does not leap. */
internal const val MAX_FRAME_NANOS = 33_333_333L

/**
 * Auto-scroll speed, in px per second, for a dragged row whose middle is at [probe] px in a viewport spanning
 * [viewportStart] to [viewportEnd] px: negative (toward the list's start) within [edge] px of the start, positive
 * within [edge] px of the end, zero elsewhere. It grows linearly from zero at the zone's inner border to [maxSpeed] at
 * the edge, and stays at [maxSpeed] past it. A viewport shorter than two zones splits between them.
 */
internal fun edgeScrollSpeed(probe: Float, viewportStart: Float, viewportEnd: Float, edge: Float, maxSpeed: Float): Float {
    if (edge <= 0f || viewportEnd <= viewportStart) return 0f
    val zone = minOf(edge, (viewportEnd - viewportStart) / 2f)
    val intoStart = (viewportStart + zone - probe) / zone
    val intoEnd = (probe - (viewportEnd - zone)) / zone
    return when {
        intoStart > 0f -> -maxSpeed * intoStart.coerceAtMost(1f)
        intoEnd > 0f -> maxSpeed * intoEnd.coerceAtMost(1f)
        else -> 0f
    }
}

/** The px to scroll over a frame of [frameNanos] at [speed] px per second (at most [MAX_FRAME_NANOS] worth). */
internal fun edgeScrollStep(speed: Float, frameNanos: Long): Float = speed * frameNanos.coerceIn(0L, MAX_FRAME_NANOS) / 1e9f

/**
 * Scrolls [state] while a drag-to-reorder holds a row near its edges, faster the closer the row is to (or the further
 * past) the edge, frame by frame until the row leaves the edge zone, the list reaches its end, or [active] turns false.
 *
 * [probe] is the held row's middle in the list's viewport (the coordinates of its laid-out items' offsets), or null to
 * pause, e.g. while the row's last move is not laid out yet. After each step that scrolled, [onScrolled] runs: the row
 * stays under the finger, so the rows passing under it should be swapped with it.
 */
@Composable
fun DragAutoScrollEffect(state: LazyListState, active: Boolean, probe: () -> Float?, onScrolled: () -> Unit) {
    val density = LocalDensity.current
    val edge = with(density) { EdgeZone.toPx() }
    val maxSpeed = with(density) { MAX_SPEED_DP_PER_SECOND.dp.toPx() }
    val latestProbe by rememberUpdatedState(probe)
    val latestOnScrolled by rememberUpdatedState(onScrolled)
    LaunchedEffect(state, active, edge, maxSpeed) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val frame = now - last
            last = now
            val middle = latestProbe() ?: continue
            // The edges are the content padding's: padding usually sits under chrome the finger cannot see past.
            val info = state.layoutInfo
            val start = (info.viewportStartOffset + info.beforeContentPadding).toFloat()
            val end = (info.viewportEndOffset - info.afterContentPadding).toFloat()
            val speed = edgeScrollSpeed(middle, start, end, edge, maxSpeed)
            if (speed == 0f) continue
            // Scrolling the state directly dispatches no nested scroll, so an enclosing sheet never moves with it.
            if (state.scrollBy(edgeScrollStep(speed, frame)) != 0f) latestOnScrolled()
        }
    }
}
