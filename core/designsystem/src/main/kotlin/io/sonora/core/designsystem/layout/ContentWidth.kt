package io.sonora.core.designsystem.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide list-like content gets on a large window ([WindowClass.isLarge]): the content keeps a readable width and is
 * centred, the rest of the window staying empty (scrolling still works there). Phones are never capped.
 */
object ContentWidth {
    /** Forms and plain lists (Settings, Converter, Export, Search field, Artists, Playlists, Folders). */
    const val LIST_DP = 720

    /** Song lists and track tables, which show more columns the more room they have. */
    const val TABLE_DP = 840

    /** The width the content takes in [availableDp] on [windowClass]: at most [maxDp]; `null` on a phone (no cap). */
    fun widthDp(windowClass: WindowClass, availableDp: Float, maxDp: Int): Float? =
        if (windowClass.isLarge) minOf(availableDp, maxDp.toFloat()) else null

    /** The empty space on each side of the content capped at [maxDp] in [availableDp]; 0 on a phone and when it all fits. */
    fun insetDp(windowClass: WindowClass, availableDp: Float, maxDp: Int): Float =
        widthDp(windowClass, availableDp, maxDp)?.let { (availableDp - it) / 2f } ?: 0f
}

/**
 * The room the content has on a large window: its [width] and the [inset] on each side that centres it. A phone has
 * [Unbounded] (`width` null, no inset), so nothing that reads it changes there.
 */
@Immutable
data class ContentArea(val width: Dp?, val inset: Dp) {
    companion object {
        val Unbounded = ContentArea(width = null, inset = 0.dp)
    }
}

/** The [ContentArea] of the nearest [ContentWidthBox]; [ContentArea.Unbounded] outside one and on phones. */
val LocalContentArea = compositionLocalOf { ContentArea.Unbounded }

/**
 * A box for list-like content: on a large window, [content] gets the [ContentArea] of at most [max] wide, centred in
 * this box ([ContentArea.inset] on each side; typically the horizontal `contentPadding` of a list, so the list still
 * scrolls across the whole width), and [LocalContentArea] tells the rows below how much room they have. On a phone it
 * is a plain box and the area is [ContentArea.Unbounded].
 */
@Composable
fun ContentWidthBox(
    modifier: Modifier = Modifier,
    max: Dp = ContentWidth.LIST_DP.dp,
    content: @Composable BoxScope.(ContentArea) -> Unit,
) {
    val windowClass = LocalWindowClass.current
    if (!windowClass.isLarge) {
        Box(modifier) { content(ContentArea.Unbounded) }
        return
    }
    BoxWithConstraints(modifier) {
        val area = ContentArea(
            width = ContentWidth.widthDp(windowClass, maxWidth.value, max.value.toInt())?.dp,
            inset = ContentWidth.insetDp(windowClass, maxWidth.value, max.value.toInt()).dp,
        )
        CompositionLocalProvider(LocalContentArea provides area) { content(area) }
    }
}

/**
 * A [LazyColumn] whose rows keep at most [max] of width on a large window, centred (see [ContentWidthBox]): the list
 * itself spans the window and the difference is added to its horizontal content padding. On a phone it is exactly a
 * [LazyColumn] with these arguments.
 */
@Composable
fun ContentWidthLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    max: Dp = ContentWidth.LIST_DP.dp,
    content: LazyListScope.() -> Unit,
) {
    if (!LocalWindowClass.current.isLarge) {
        LazyColumn(modifier, state, contentPadding, content = content)
        return
    }
    ContentWidthBox(modifier, max) { area ->
        val padding = remember(contentPadding, area.inset) { InsetPadding(contentPadding, area.inset) }
        LazyColumn(Modifier.fillMaxSize(), state, padding, content = content)
    }
}

/**
 * [base] with [inset] added at the start and end. The top and bottom stay [base]'s own, read when the list is laid out,
 * so a base that follows the keyboard or a bar (read lazily) keeps doing so.
 */
@Immutable
private class InsetPadding(private val base: PaddingValues, private val inset: Dp) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = base.calculateLeftPadding(layoutDirection) + inset

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = base.calculateRightPadding(layoutDirection) + inset

    override fun calculateTopPadding(): Dp = base.calculateTopPadding()

    override fun calculateBottomPadding(): Dp = base.calculateBottomPadding()

    override fun equals(other: Any?): Boolean = other is InsetPadding && other.base == base && other.inset == inset

    override fun hashCode(): Int = 31 * base.hashCode() + inset.hashCode()
}

/**
 * Keeps this element at most [max] wide and centred in the room it is given, on a large window; a phone's is
 * untouched. For one block in a column that otherwise spans the window (a header above a wide grid); lists use
 * [ContentWidthLazyColumn] so the margins stay scrollable.
 */
@Composable
fun Modifier.contentWidth(max: Dp = ContentWidth.LIST_DP.dp): Modifier {
    if (!LocalWindowClass.current.isLarge) return this
    return this.layout { measurable, constraints ->
        val available = constraints.maxWidth
        if (available == Constraints.Infinity) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val width = minOf(available, max.roundToPx())
        val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = constraints.maxHeight))
        layout(available, placeable.height) { placeable.place((available - width) / 2, 0) }
    }
}
