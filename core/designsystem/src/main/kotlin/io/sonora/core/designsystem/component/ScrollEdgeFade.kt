package io.sonora.core.designsystem.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The iOS scroll edge of a translucent panel: where content scrolls under the top or bottom of a panel's scrolling area, it
 * fades out softly instead of being cut by a hard line. An edge fades only while content lies beyond it, ramping in
 * as the content starts to slide under, so a panel at rest shows its first and last rows crisp.
 */

/** How far the fade reaches into the scrolling area. */
val ScrollEdgeFadeLength: Dp = 20.dp

/**
 * How strongly a scroll edge fades (0 not at all, 1 fully) when [hidden] pixels of content lie beyond it: in step
 * with the content over the first [ramp] pixels, so the fade grows as the content slides under rather than popping in.
 */
fun scrollEdgeFadeStrength(hidden: Float, ramp: Float): Float = when {
    hidden <= 0f -> 0f
    ramp <= 0f -> 1f
    else -> (hidden / ramp).coerceIn(0f, 1f)
}

/** Content scrolled out before a lazy list's viewport: the first item's scrolled part, or at least a whole item. */
internal fun lazyHiddenBefore(firstVisibleIndex: Int, firstVisibleOffset: Int): Float =
    if (firstVisibleIndex > 0) Float.POSITIVE_INFINITY else firstVisibleOffset.coerceAtLeast(0).toFloat()

/**
 * Content still to come after a lazy list's viewport: at least a whole item while the last item is not shown,
 * otherwise what of it (and of the [afterContentPadding]) runs past [viewportEnd]. [lastVisibleEnd] is where the last
 * visible item ends, in the same coordinates as [viewportEnd].
 */
internal fun lazyHiddenAfter(lastVisibleIndex: Int, itemCount: Int, lastVisibleEnd: Int, viewportEnd: Int, afterContentPadding: Int): Float =
    when {
        itemCount == 0 || lastVisibleIndex < 0 -> 0f
        lastVisibleIndex < itemCount - 1 -> Float.POSITIVE_INFINITY
        else -> (lastVisibleEnd + afterContentPadding - viewportEnd).coerceAtLeast(0).toFloat()
    }

/**
 * Fades this scrolling area's start and end edges (top and bottom when [orientation] is vertical) while [hiddenBefore]
 * and [hiddenAfter] pixels of content lie beyond them. Put it outside the scroll modifier, so it fades the viewport.
 * Both are read in the draw phase only: scrolling redraws the fade, it never recomposes.
 */
fun Modifier.scrollEdgeFade(
    orientation: Orientation,
    hiddenBefore: () -> Float,
    hiddenAfter: () -> Float,
    length: Dp = ScrollEdgeFadeLength,
): Modifier = this
    // Offscreen, so the mask below fades this content only, not the translucent surface under it.
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = length.toPx()
        val start = scrollEdgeFadeStrength(hiddenBefore(), fade)
        val end = scrollEdgeFadeStrength(hiddenAfter(), fade)
        if (start > 0f) fadeEdge(orientation, atStart = true, strength = start, fade = fade)
        if (end > 0f) fadeEdge(orientation, atStart = false, strength = end, fade = fade)
    }

/** [scrollEdgeFade] for a [ScrollState] (a `verticalScroll` or `horizontalScroll` column or row). */
fun Modifier.scrollEdgeFade(state: ScrollState, orientation: Orientation = Orientation.Vertical, length: Dp = ScrollEdgeFadeLength): Modifier =
    scrollEdgeFade(
        orientation,
        hiddenBefore = { state.value.toFloat() },
        hiddenAfter = { (state.maxValue - state.value).coerceAtLeast(0).toFloat() },
        length = length,
    )

/** [scrollEdgeFade] for a lazy list scrolling along [orientation]. */
fun Modifier.scrollEdgeFade(state: LazyListState, orientation: Orientation = Orientation.Vertical, length: Dp = ScrollEdgeFadeLength): Modifier =
    scrollEdgeFade(
        orientation,
        hiddenBefore = { lazyHiddenBefore(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
        hiddenAfter = {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            lazyHiddenAfter(
                lastVisibleIndex = last?.index ?: -1,
                itemCount = info.totalItemsCount,
                lastVisibleEnd = last?.let { it.offset + it.size } ?: 0,
                viewportEnd = info.viewportEndOffset,
                afterContentPadding = info.afterContentPadding,
            )
        },
        length = length,
    )

/**
 * Masks one edge band [fade] pixels deep: the content keeps `1 - strength` of its alpha at the edge, rising smoothly
 * to all of it at the band's inner side.
 */
private fun DrawScope.fadeEdge(orientation: Orientation, atStart: Boolean, strength: Float, fade: Float) {
    val vertical = orientation == Orientation.Vertical
    val extent = if (vertical) size.height else size.width
    val depth = fade.coerceAtMost(extent / 2f)
    if (depth <= 0f) return
    val edge = Color.Black.copy(alpha = 1f - strength)
    // Eased: an S-curve reads as a soft translucent edge rather than a linear wipe.
    val stops = arrayOf(0f to edge, 0.5f to lerpAlpha(edge, 0.7f), 1f to Color.Black)
    val bandStart = if (atStart) 0f else extent - depth
    val from = if (atStart) bandStart else extent
    val to = if (atStart) depth else bandStart
    val brush = if (vertical) {
        Brush.verticalGradient(*stops, startY = from, endY = to)
    } else {
        Brush.horizontalGradient(*stops, startX = from, endX = to)
    }
    val topLeft = if (vertical) Offset(0f, bandStart) else Offset(bandStart, 0f)
    val band = if (vertical) Size(size.width, depth) else Size(depth, size.height)
    drawRect(brush, topLeft, band, blendMode = BlendMode.DstIn)
}

/** [edge]'s alpha moved [fraction] of the way to opaque. */
private fun lerpAlpha(edge: Color, fraction: Float): Color = Color.Black.copy(alpha = edge.alpha + (1f - edge.alpha) * fraction)
