package io.sonora.feature.library.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.domain.model.Track
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// The thin Compose glue of drag-to-select; the rules (anchor, painting, edge speed) are in DragSelect.kt.

/** How wide the trailing strip is where a press counts as on a row's selection circle (the circle plus a thumb's slack). */
private val CircleZone = 56.dp

/** How close to the visible edge of the list a drag starts scrolling it. */
private val EdgeZone = 72.dp

/** How fast the list scrolls under a finger right at (or past) its visible edge, in dp per second. */
private const val MAX_SPEED_DP_PER_SECOND = 1100f

/** The longest frame one auto-scroll step covers (two 60 Hz frames), so a stalled frame does not leap. */
private const val MAX_FRAME_NANOS = 33_333_333L

/** What a drag reads when it runs, kept current without restarting the gesture. */
private class DragSetup(
    val shown: List<String>,
    val topPx: Float,
    val bottomPx: Float,
    val circleZonePx: Float,
    val edgeZonePx: Float,
    val maxSpeedPx: Float,
    val haptics: HapticFeedback,
)

/**
 * Registers this row (by its selection [key]) with [selection] while selecting, so a drag over the list can tell which
 * row is under the finger. Put it on the row's outermost modifier.
 */
@Composable
internal fun Modifier.dragSelectRow(selection: SongSelection?, key: String): Modifier {
    DisposableEffect(selection, key) { onDispose { selection?.rowCoordinates?.remove(key) } }
    return if (selection?.active == true) onGloballyPositioned { selection.rowCoordinates[key] = it } else this
}

/**
 * A press on a row's selection circle, then a vertical drag, paints the rows from that row to the finger with the
 * circle's new state (see [DragPaint]); the list scrolls while the finger is at its visible top or bottom edge, and
 * the painting follows. A tap on the circle, and any drag that starts elsewhere, behave as before: the gesture is
 * watched on the initial pass and only claims the pointer once a vertical drag from a circle is under way.
 *
 * [shown] are the list's rows in order. The visible part of the list ends [topInset] below its top and [bottomInset]
 * above its bottom (the floating bars and the padding under them), where the auto-scroll zones begin.
 */
@Composable
internal fun Modifier.dragSelect(
    selection: SongSelection?,
    list: LazyListState,
    shown: List<Pair<String, Track>>,
    topInset: Dp = TranslucentTopBarHeight,
    bottomInset: Dp = BottomChromePadding + SelectionBarSpace,
): Modifier {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val keys = remember(shown) { shown.map { it.first } }
    val setup = rememberUpdatedState(
        with(density) {
            DragSetup(keys, topInset.toPx(), bottomInset.toPx(), CircleZone.toPx(), EdgeZone.toPx(), MAX_SPEED_DP_PER_SECOND.dp.toPx(), haptics)
        },
    )
    val host = remember { ListHost() }
    return this
        .onGloballyPositioned { host.coordinates = it }
        .pointerInput(selection, list) {
            if (selection != null) dragSelectGestures(selection, list, setup, host)
        }
}

/** The list's own coordinates, to put the rows' positions in its space. */
private class ListHost {
    var coordinates: LayoutCoordinates? = null
}

private fun rowSpans(selection: SongSelection, host: ListHost): List<RowSpan> {
    val list = host.coordinates?.takeIf { it.isAttached } ?: return emptyList()
    return selection.rowCoordinates.mapNotNull { (key, row) ->
        if (!row.isAttached) return@mapNotNull null
        val top = list.localPositionOf(row, Offset.Zero).y
        RowSpan(key, top, top + row.size.height)
    }
}

/** One drag in progress: its painting and the row the finger was last over. */
private class ActiveDrag(val paint: DragPaint) {
    var lastKey: String? = null
}

private suspend fun PointerInputScope.dragSelectGestures(
    selection: SongSelection,
    list: LazyListState,
    setup: State<DragSetup>,
    host: ListHost,
) = coroutineScope {
    var drag: ActiveDrag? = null
    var fingerY = 0f
    val dragging = MutableStateFlow(false)

    fun paintUnder(y: Float) {
        val active = drag ?: return
        val key = nearestRow(y, rowSpans(selection, host)) ?: return
        if (key == active.lastKey) return
        active.lastKey = key
        selection.paint(active.paint.extendTo(key))
    }

    // While a drag is on, every frame: scroll if the finger is at an edge (even held still), then paint under it.
    launch {
        dragging.collectLatest { on ->
            if (!on) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val frame = (now - last).coerceIn(0L, MAX_FRAME_NANOS)
                last = now
                val s = setup.value
                val speed = autoScrollSpeed(fingerY, s.topPx, size.height - s.bottomPx, s.edgeZonePx, s.maxSpeedPx)
                if (speed != 0f) list.scrollBy(speed * frame / 1e9f)
                paintUnder(fingerY)
            }
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val s = setup.value
        if (!selection.active || !inCircleZone(down.position.x, size.width.toFloat(), s.circleZonePx)) return@awaitEachGesture
        val anchor = rowContaining(down.position.y, rowSpans(selection, host)) ?: return@awaitEachGesture
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (drag == null) {
                    val moved = change.position - down.position
                    if (maxOf(abs(moved.x), abs(moved.y)) <= viewConfiguration.touchSlop) continue
                    if (abs(moved.x) > abs(moved.y)) break
                    val paint = DragPaint.begin(setup.value.shown, selection.state.keys, anchor) ?: break
                    drag = ActiveDrag(paint)
                    s.haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    fingerY = change.position.y
                    paintUnder(down.position.y)
                    dragging.value = true
                }
                change.consume()
                fingerY = change.position.y
            }
        } finally {
            dragging.value = false
            drag = null
        }
    }
}
