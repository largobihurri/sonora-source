package io.sonora.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * A lazy list's size as the fast scroller estimates it: [itemCount] items of [averageItemPx] (measured on the visible
 * ones, per item, so a grid row of two counts as two half-height items), [paddingPx] of content padding and a
 * [viewportPx] tall viewport. [lanes] is how many items share a row: 1 for a list, the column count for a grid. A grid
 * may start with [leadingItems] full-span items (a header) that together take [leadingPx] up to the first tile row:
 * they are not tiles, so they are measured apart; the tiles after them fill whole rows.
 */
@Immutable
data class FastScrollExtent(
    val itemCount: Int,
    val averageItemPx: Float,
    val paddingPx: Float,
    val viewportPx: Float,
    val lanes: Int = 1,
    val leadingItems: Int = 0,
    val leadingPx: Float = 0f,
) {
    private val laneCount: Int get() = lanes.coerceAtLeast(1)

    /** One row of tiles, in pixels. */
    val rowPx: Float get() = averageItemPx * laneCount

    /** How many rows the tiles after the leading items fill; the last row counts whole even with one tile in it. */
    val tileRows: Int get() = ((itemCount - leadingItems).coerceAtLeast(0) + laneCount - 1) / laneCount

    /** How far the content can scroll, never negative. */
    val rangePx: Float get() = (leadingPx + tileRows * rowPx + paddingPx - viewportPx).coerceAtLeast(0f)
}

/** A visible grid item as the extent estimate needs it: its [index], [top] and [height] in pixels, and whether it spans every column. */
@Immutable
data class GridItemBounds(val index: Int, val top: Int, val height: Int, val fullSpan: Boolean)

/** Where a fast scroll jumps: the first visible item and how many pixels of it are scrolled past. */
@Immutable
data class FastScrollTarget(val index: Int, val offset: Int)

/** The fast scroller's mapping between scroll position, fraction and thumb position. Pure, so it is unit-tested. */
object FastScrollMath {

    /**
     * How far along its range a list whose first visible item is [firstIndex], scrolled by [firstOffset], is (0..1).
     * Leading items count as themselves, then the tiles by whole rows: in a grid the first visible item is the first
     * of its row, and a header in front of the rows must not count as half a row.
     */
    fun scrollFraction(firstIndex: Int, firstOffset: Int, extent: FastScrollExtent): Float {
        val range = extent.rangePx
        if (extent.itemCount <= 0 || range <= 0f) return 0f
        val lanes = extent.lanes.coerceAtLeast(1)
        val leading = extent.leadingItems
        val px = if (firstIndex < leading) {
            firstIndex * (extent.leadingPx / leading) + firstOffset
        } else {
            extent.leadingPx + (firstIndex - leading) / lanes * extent.rowPx + firstOffset
        }
        return (px / range).coerceIn(0f, 1f)
    }

    /** The item and offset to jump to so the list sits at [fraction] of its range; see [targetAtPx]. */
    fun scrollTarget(fraction: Float, extent: FastScrollExtent): FastScrollTarget =
        targetAtPx(fraction.coerceIn(0f, 1f) * extent.rangePx, extent)

    /**
     * The item and offset to jump to so the list sits [px] into its range; the top for an empty list. Inside the
     * leading items it is an offset into the first; after them the item is always the first lane of a row and the
     * offset runs the whole row: a grid scrolled to the second item of a row lands on the same row as the first, so
     * targets that fell inside a row would run the offset twice per row and the grid would move forward, back and
     * forward again. The estimate is only an estimate, so the end is not a position: [px] at or past the range is the
     * last row pushed a viewport and a row beyond its top, which the list clamps onto its true end whatever its real
     * length is. At or before 0 is the top.
     */
    fun targetAtPx(px: Float, extent: FastScrollExtent): FastScrollTarget {
        val range = extent.rangePx
        if (extent.itemCount <= 0 || range <= 0f || extent.averageItemPx <= 0f || px <= 0f) return FastScrollTarget(0, 0)
        val lanes = extent.lanes.coerceAtLeast(1)
        val lastRow = (extent.tileRows - 1).coerceAtLeast(0)
        if (px >= range) {
            val index = (extent.leadingItems + lastRow * lanes).coerceIn(0, extent.itemCount - 1)
            return FastScrollTarget(index, (extent.viewportPx + extent.rowPx).roundToInt())
        }
        if (px < extent.leadingPx) return FastScrollTarget(0, px.roundToInt())
        val intoTiles = px - extent.leadingPx
        val row = floor(intoTiles / extent.rowPx).toInt().coerceIn(0, lastRow)
        val index = (extent.leadingItems + row * lanes).coerceIn(0, extent.itemCount - 1)
        return FastScrollTarget(index, (intoTiles - row * extent.rowPx).roundToInt())
    }

    /**
     * How many viewports past the estimated range the very end of the track aims. The extent is estimated from the
     * rows on screen at the grab (a header or a last short row can be taller or shorter than the average), so the
     * list's real end can be a few hundred px beyond or before the estimate's: the list aims past it and stops on its
     * real end.
     */
    const val END_SLACK_VIEWPORTS = 1f

    /**
     * Where the list aims for a finger at [fraction] in a drag that started at [startFraction]: the plain position
     * [fraction] * range, except between the start and each end, at most the end's last viewport (and half the range),
     * which is stretched towards [END_SLACK_VIEWPORTS] viewports past the estimate at that end. Monotone and
     * continuous, and the goal at the start is where the list is, so it adds no jump; only the finger's last stretch
     * toward an end runs a little faster than the rest. A drag that starts at an end is not stretched on that side:
     * the list is really there, and the stretch would make the finger climb back a viewport's worth before the list
     * moved (QA round 4: 54 px of finger from the top of Albumes).
     */
    fun glideGoalPx(fraction: Float, extent: FastScrollExtent, startFraction: Float): Float {
        val range = extent.rangePx
        val px = fraction.coerceIn(0f, 1f) * range
        val zone = minOf(extent.viewportPx, range / 2f)
        if (range <= 0f || zone <= 0f) return px
        val slack = END_SLACK_VIEWPORTS * extent.viewportPx
        val startPx = startFraction.coerceIn(0f, 1f) * range
        val high = maxOf(range - zone, startPx)
        val low = minOf(zone, startPx)
        return when {
            px > high -> px + (px - high) / (range - high) * slack
            px < low -> px - (low - px) / low * slack
            else -> px
        }
    }

    /**
     * The fraction after a held thumb moves from [fromTopPx] to [toTopPx] (its top, under the finger) along a track
     * whose thumb can travel [travelPx] now, starting from [fraction]. The step is the finger's share of what is left
     * in its direction: toward the end `(1 - fraction) / (travel - from)` per px, toward the start `fraction / from`.
     * On a steady track with the thumb where the fraction puts it, that is plain `1 / travel`; when the track grows or
     * shrinks under the finger (the bottom chrome collapsing or expanding) the fraction does not move (the list stays)
     * and the rest of the drag shares out what is left, so the thumb stays under the finger and still reaches each end
     * of the track exactly when the list reaches that end. Positions beyond the track count as its ends.
     */
    fun dragFraction(fraction: Float, fromTopPx: Float, toTopPx: Float, travelPx: Float): Float {
        if (travelPx <= 0f) return fraction
        val from = fromTopPx.coerceIn(0f, travelPx)
        val to = toTopPx.coerceIn(0f, travelPx)
        return when {
            to == from -> fraction
            to > from -> if (to >= travelPx) 1f else fraction + (to - from) * (1f - fraction) / (travelPx - from)
            else -> if (to <= 0f) 0f else fraction - (from - to) * fraction / from
        }.coerceIn(0f, 1f)
    }

    /**
     * A grid's extent from the items on screen. Full-span items from index 0 (a header) are the leading items: their
     * [FastScrollExtent.leadingPx] is the distance to the first tile row. The tiles' row height is the distance between
     * the rows on screen (spacing included), or the one visible row's height; with no tile on screen, the average of
     * all the visible items.
     */
    fun gridExtent(itemCount: Int, lanes: Int, items: List<GridItemBounds>, paddingPx: Float, viewportPx: Float): FastScrollExtent {
        val laneCount = lanes.coerceAtLeast(1)
        val ordered = items.sortedBy { it.index }
        val leading = ordered.withIndex().takeWhile { (i, item) -> item.index == i && item.fullSpan }.map { it.value }
        val tiles = ordered.filter { !it.fullSpan }
        val rowTops = tiles.map { it.top }.distinct().sorted()
        val rowPitch = when {
            rowTops.size >= 2 -> (rowTops.last() - rowTops.first()).toFloat() / (rowTops.size - 1)
            tiles.isNotEmpty() -> tiles.maxOf { it.height }.toFloat()
            ordered.isNotEmpty() -> (ordered.maxOf { it.top + it.height } - ordered.minOf { it.top }).toFloat() / ordered.size * laneCount
            else -> 0f
        }
        val firstTile = ordered.firstOrNull { it.index == leading.size && !it.fullSpan }
        val leadingPx = when {
            leading.isEmpty() -> 0f
            firstTile != null -> (firstTile.top - leading.first().top).toFloat()
            else -> (leading.last().top + leading.last().height - leading.first().top).toFloat()
        }
        return FastScrollExtent(itemCount, rowPitch / laneCount, paddingPx, viewportPx, laneCount, leading.size, leadingPx)
    }

    /** The thumb's top within a [trackPx] tall track for [fraction]; the thumb ([thumbPx]) never leaves the track. */
    fun thumbOffset(fraction: Float, trackPx: Float, thumbPx: Float): Float =
        fraction.coerceIn(0f, 1f) * (trackPx - thumbPx).coerceAtLeast(0f)

    /** The fraction for a thumb whose top is at [offsetPx]; 0 when the track is no taller than the thumb. */
    fun fractionAtThumbOffset(offsetPx: Float, trackPx: Float, thumbPx: Float): Float {
        val travel = trackPx - thumbPx
        if (travel <= 0f) return 0f
        return (offsetPx / travel).coerceIn(0f, 1f)
    }

    /**
     * Whether a list [remainingPx] short of its target is too far to scroll across: more than a viewport away (a fast
     * sweep of the finger) it jumps with [scrollTarget] instead, since every pixel in between is off screen anyway.
     */
    fun shouldJump(remainingPx: Float, viewportPx: Float): Boolean = abs(remainingPx) > viewportPx
}

/**
 * A held thumb: drawn under the finger ([thumbTop], the grab's top plus the finger's travel) while the list's
 * [fraction] moves with the finger by [FastScrollMath.dragFraction]. The two are kept apart because the track's length
 * can change under a held finger (the bottom chrome minimises as the list scrolls toward its end and expands as it
 * scrolls back): drawing the thumb at `fraction * travel` then put it up to ~170 px off the finger whenever a drag went
 * the other way from the last one (QA round 4), and taking the fraction from the thumb's position would make the list
 * jump. Grabbed with the list at [startFraction] and the thumb's top at [startTopPx].
 */
class ThumbDrag(startFraction: Float, private val startTopPx: Float) {
    var fraction = startFraction.coerceIn(0f, 1f)
        private set
    private var top = startTopPx

    /** The finger has moved [fingerDeltaPx] from the grab, on a track whose thumb can travel [travelPx] now. */
    fun move(fingerDeltaPx: Float, travelPx: Float) {
        val to = startTopPx + fingerDeltaPx
        fraction = FastScrollMath.dragFraction(fraction, top, to, travelPx)
        top = to
    }

    /** The thumb's top on a track whose thumb can travel [travelPx]: under the finger, within the track. */
    fun thumbTop(travelPx: Float): Float = top.coerceIn(0f, travelPx.coerceAtLeast(0f))
}

/** What the thumb needs from a lazy list or grid. */
private interface FastScrollable {
    val canScroll: Boolean
    val fraction: Float

    /** The list's size as estimated from the items on screen now. */
    fun extent(): FastScrollExtent
    suspend fun scrollTo(target: FastScrollTarget)

    /**
     * Scrolls forward by [px] (backward when negative); fractions of a pixel carry over to the next call. Returns how
     * much of it the list took: less than [px] means it reached an end.
     */
    suspend fun scrollBy(px: Float): Float
}

private class ListScrollable(private val state: LazyListState) : FastScrollable {
    override val canScroll get() = state.canScrollForward || state.canScrollBackward
    override val fraction: Float
        get() = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> FastScrollMath.scrollFraction(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, extent(state.layoutInfo))
        }

    override fun extent() = extent(state.layoutInfo)

    override suspend fun scrollTo(target: FastScrollTarget) = state.scrollToItem(target.index, target.offset)

    override suspend fun scrollBy(px: Float): Float = state.scrollBy(px)

    private fun extent(info: LazyListLayoutInfo): FastScrollExtent {
        val visible = info.visibleItemsInfo
        val covered = if (visible.isEmpty()) 0 else visible.last().offset + visible.last().size - visible.first().offset
        return FastScrollExtent(
            itemCount = info.totalItemsCount,
            averageItemPx = if (visible.isEmpty()) 0f else covered.toFloat() / visible.size,
            paddingPx = (info.beforeContentPadding + info.afterContentPadding).toFloat(),
            viewportPx = info.viewportSize.height.toFloat(),
        )
    }
}

private class GridScrollable(private val state: LazyGridState) : FastScrollable {
    override val canScroll get() = state.canScrollForward || state.canScrollBackward
    override val fraction: Float
        get() = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> FastScrollMath.scrollFraction(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, extent(state.layoutInfo))
        }

    override fun extent() = extent(state.layoutInfo)

    override suspend fun scrollTo(target: FastScrollTarget) = state.scrollToItem(target.index, target.offset)

    override suspend fun scrollBy(px: Float): Float = state.scrollBy(px)

    // The header's size is only known while it is on screen; remember it for when the grid is scrolled past it.
    private var leading: Pair<Int, Float>? = null

    private fun extent(info: LazyGridLayoutInfo): FastScrollExtent {
        val measured = FastScrollMath.gridExtent(
            itemCount = info.totalItemsCount,
            lanes = info.maxSpan,
            items = info.visibleItemsInfo.map { GridItemBounds(it.index, it.offset.y, it.size.height, fullSpan = it.span >= info.maxSpan) },
            paddingPx = (info.beforeContentPadding + info.afterContentPadding).toFloat(),
            viewportPx = info.viewportSize.height.toFloat(),
        )
        if (measured.leadingItems > 0) {
            leading = measured.leadingItems to measured.leadingPx
            return measured
        }
        val seen = leading
        val headerScrolledPast = info.visibleItemsInfo.firstOrNull()?.let { it.index > 0 } == true
        return if (seen != null && headerScrolledPast) measured.copy(leadingItems = seen.first, leadingPx = seen.second) else measured
    }
}

/**
 * A fast-scroll thumb for [state]: a pill in the accent colour on the trailing edge whose position follows the scroll.
 * Idle it is thin and translucent; touched it grows, turns opaque (with a light tick) and dragging it glides the list
 * through its whole length (see [followFinger]); released it shrinks back. Hidden while the list fits on screen. Place it over the list, aligned to
 * the top end; its track runs from [topPadding] to [bottomPadding] above the bottom. Only the thumb itself takes
 * touches, so list swipes elsewhere on the edge are left alone.
 */
@Composable
fun FastScrollThumb(state: LazyListState, modifier: Modifier = Modifier, topPadding: Dp = 8.dp, bottomPadding: Dp = BottomChromePadding) {
    val scrollable = remember(state) { ListScrollable(state) }
    FastScrollThumb(scrollable, modifier, topPadding, bottomPadding)
}

/** [FastScrollThumb] for a lazy grid. */
@Composable
fun FastScrollThumb(state: LazyGridState, modifier: Modifier = Modifier, topPadding: Dp = 8.dp, bottomPadding: Dp = BottomChromePadding) {
    val scrollable = remember(state) { GridScrollable(state) }
    FastScrollThumb(scrollable, modifier, topPadding, bottomPadding)
}

@Composable
private fun FastScrollThumb(scrollable: FastScrollable, modifier: Modifier, topPadding: Dp, bottomPadding: Dp) {
    val canScroll by remember(scrollable) { derivedStateOf { scrollable.canScroll } }
    if (!canScroll) return
    val haptics = LocalHapticFeedback.current
    val color = MaterialTheme.colorScheme.primary
    var grabbed by remember { mutableStateOf(false) }
    // While dragging, the list follows the drag's fraction (NaN when idle), smoothed, and the thumb is drawn under the
    // finger at dragTop (see ThumbDrag).
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }
    var dragTop by remember { mutableFloatStateOf(Float.NaN) }
    // Let go where the track changed length under the finger, the held thumb and `fraction * travel` differ: the thumb
    // starts from where it was held and slides onto the list's position instead of jumping.
    var releaseShift by remember { mutableFloatStateOf(0f) }
    var releases by remember { mutableIntStateOf(0) }
    // The list's size as measured when the thumb was grabbed. Re-measuring on every step would estimate it from rows
    // of another height (section headers, two-line rows), so the same fraction would land somewhere else each time
    // and a still finger would make the list shake.
    val dragExtent = remember { arrayOfNulls<FastScrollExtent>(1) }
    // The thumb's travel when it was grabbed, the unit the finger smoother measures the finger in.
    val dragTravel = remember { FloatArray(1) }
    val chrome = LocalBottomChrome.current
    var trackPx by remember { mutableIntStateOf(0) }
    val grow by animateFloatAsState(if (grabbed) 1f else 0f, spring(Spring.DampingRatioNoBouncy, GROW_STIFFNESS), label = "fastScrollGrow")

    LaunchedEffect(releases) {
        val release = releases
        if (release == 0 || releaseShift == 0f) return@LaunchedEffect
        animate(releaseShift, 0f, animationSpec = spring(Spring.DampingRatioNoBouncy, RELEASE_SHIFT_STIFFNESS)) { value, _ ->
            if (releases == release && !grabbed) releaseShift = value
        }
    }

    val density = LocalDensity.current
    LaunchedEffect(scrollable) {
        while (true) {
            snapshotFlow { dragFraction }.first { !it.isNaN() }
            val extent = dragExtent[0] ?: continue
            scrollable.followFinger(
                extent,
                travelPx = dragTravel[0],
                bandPx = with(density) { FingerBand.toPx() },
                restBandPx = with(density) { RestFingerBand.toPx() },
                fingerFraction = { dragFraction },
                onScrolled = chrome::onFastScroll,
                replacedByNewDrag = { dragExtent[0].let { it != null && it !== extent } },
            )
        }
    }

    Box(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.End))
            .padding(top = topPadding, bottom = bottomPadding)
            .width(TouchWidth)
            .onSizeChanged { trackPx = it.height },
    ) {
        // The thumb's top as last laid out: drag positions are local to the moving thumb, so this turns them into track positions.
        val laidOut = remember { FloatArray(1) }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(TouchWidth, ThumbBox)
                .offset {
                    val travel = (trackPx - ThumbBox.toPx()).coerceAtLeast(0f)
                    val top = if (dragTop.isNaN()) {
                        (FastScrollMath.thumbOffset(scrollable.fraction, trackPx.toFloat(), ThumbBox.toPx()) + releaseShift).coerceIn(0f, travel)
                    } else {
                        dragTop.coerceIn(0f, travel)
                    }
                    laidOut[0] = top
                    IntOffset(0, top.roundToInt())
                }
                .pointerInput(scrollable) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        grabbed = true
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        val thumbPx = ThumbBox.toPx()
                        fun travel() = (trackPx - thumbPx).coerceAtLeast(0f)
                        // The thumb stays under the finger from where it is drawn and the list's fraction moves with the
                        // finger from where the list is; the track may change length under it (the bottom chrome
                        // minimising or expanding), so the travel is read live.
                        val drag = ThumbDrag(scrollable.fraction, laidOut[0])
                        val startFinger = laidOut[0] + down.position.y
                        dragExtent[0] = scrollable.extent()
                        dragTravel[0] = travel()
                        dragTop = drag.thumbTop(travel())
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val finger = laidOut[0] + change.position.y
                                drag.move(finger - startFinger, travel())
                                dragTop = drag.thumbTop(travel())
                                dragFraction = drag.fraction
                            }
                        } finally {
                            grabbed = false
                            val held = drag.thumbTop(travel())
                            releaseShift = held - FastScrollMath.thumbOffset(scrollable.fraction, trackPx.toFloat(), thumbPx)
                            releases++
                            dragTop = Float.NaN
                            dragFraction = Float.NaN
                            dragExtent[0] = null
                        }
                    }
                }
                .drawBehind {
                    val width = lerp(IdleWidth.toPx(), GrabbedWidth.toPx(), grow)
                    val height = lerp(IdleHeight.toPx(), GrabbedHeight.toPx(), grow)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(size.width - EdgeInset.toPx() - width, (size.height - height) / 2f),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(width / 2f),
                        alpha = lerp(IDLE_ALPHA, 1f, grow),
                    )
                },
        )
    }
}

/**
 * Moves the list after the finger while the thumb is held, and for a moment after it is let go, until the list has
 * caught up. The thumb jumps by the finger's whole travel at once, but the track is a fraction of the content (about
 * 30 px of list for every pixel of finger), so applying each new fraction with [FastScrollable.scrollTo] moved the list
 * in visible steps with stillness in between. Instead the finger goes through a [FingerSmoother] (a dead band that
 * keeps a resting or jittering finger from moving anything, then an alpha-beta tracker whose speed carries the list
 * between the finger's pixel steps) and, once per frame, the list moves to the smoothed finger by
 * [FastScrollable.scrollBy], which accumulates the fractions of a pixel: the list moves every frame, about 25 ms behind
 * the finger at any speed, and stops on it without passing it or moving back.
 *
 * The list's position is tracked in the pixel space of [extent] (frozen at grab) starting from where the list is, so
 * it advances by real pixels. The goal comes from [FastScrollMath.glideGoalPx], which aims past the estimate at both
 * ends: a list stops on its real end whatever the estimate was, [FastScrollable.scrollBy] reporting it took less than
 * asked, and the tracked position is then the end's. When the target is more than a viewport away (a fast sweep of the
 * finger) it jumps with [FastScrollMath.targetAtPx] instead, then follows on from there. [fingerFraction] is the
 * finger's fraction, NaN once released; after release the smoother aims at the finger itself (no band) and the list
 * finishes the last bit at the finger's speed (a frame or two) rather than stopping short of where the thumb was let
 * go. It ends when the list lands on the finger or reaches an end after release, when [replacedByNewDrag], or when
 * another scroll (the user swiping the list) takes the list over. [onScrolled] gets how far the list moved (positive
 * toward its end), so the bottom chrome can follow the thumb like it follows a swipe.
 */
private suspend fun FastScrollable.followFinger(
    extent: FastScrollExtent,
    travelPx: Float,
    bandPx: Float,
    restBandPx: Float,
    fingerFraction: () -> Float,
    onScrolled: (Float) -> Unit,
    replacedByNewDrag: () -> Boolean,
) {
    var finger = fingerFraction().takeUnless { it.isNaN() } ?: return
    val smoother = FingerSmoother(travelPx, bandPx, restBandPx)
    val start = fraction
    smoother.reset(start)
    var position = start * extent.rangePx
    var released = false
    var lastFrame = withFrameNanos { it }
    while (true) {
        val frame = withFrameNanos { it }
        val elapsed = frame - lastFrame
        lastFrame = frame
        if (replacedByNewDrag()) return
        val raw = fingerFraction()
        if (raw.isNaN()) {
            if (!released) smoother.settle(finger)
            released = true
        } else {
            finger = raw
        }
        val targetPx = FastScrollMath.glideGoalPx(smoother.update(finger, elapsed), extent, start)
        if (position == targetPx) {
            if (released) return
            continue
        }
        try {
            if (FastScrollMath.shouldJump(targetPx - position, extent.viewportPx)) {
                onScrolled(targetPx - position)
                scrollTo(FastScrollMath.targetAtPx(targetPx, extent))
                position = targetPx.coerceIn(0f, extent.rangePx)
            } else {
                val asked = targetPx - position
                val took = scrollBy(asked)
                onScrolled(took)
                if (abs(asked - took) > REACHED_END_PX) {
                    // The list ended before the estimate did (or after it): its end is the position now.
                    position = if (asked > 0f) extent.rangePx else 0f
                    if (released) return
                } else {
                    position = targetPx
                }
            }
        } catch (e: CancellationException) {
            // Another scroll took the list over: stop following the finger. Our own cancellation still propagates.
            currentCoroutineContext().ensureActive()
            return
        }
    }
}

/** A scroll that took this much less than asked reached an end of the list. */
private const val REACHED_END_PX = 0.5f

private val TouchWidth = 48.dp
private val ThumbBox = 80.dp
private val IdleWidth = 4.dp
private val IdleHeight = 44.dp
private val GrabbedWidth = 7.dp
private val GrabbedHeight = 76.dp
private val EdgeInset = 4.dp

/**
 * The finger smoother's dead band: a held finger that rests, or jitters by up to about this much either way (a touch
 * screen reports a resting finger moving by a pixel or two), does not move the list; a moving one drags the list's
 * target along this far behind it.
 */
private val FingerBand = 2.dp

/**
 * The band before the finger has clearly moved (see [FingerSmoother]): a resting finger on the QA phone jitters by up
 * to 4 px (~1.1 dp) either way, 8 px from one extreme to the other, more than [FingerBand]; 2.5 dp covers it wherever
 * the grab lands, at the cost of ~1.75 px more finger before a drag starts (~50 ms at 33 px/s).
 */
private val RestFingerBand = 2.5.dp
private const val IDLE_ALPHA = 0.38f

/** A critically damped spring that settles in about 170 ms. */
private const val GROW_STIFFNESS = 800f

/** The released thumb's slide onto the list's position: critically damped, about 250 ms. */
private const val RELEASE_SHIFT_STIFFNESS = 400f
