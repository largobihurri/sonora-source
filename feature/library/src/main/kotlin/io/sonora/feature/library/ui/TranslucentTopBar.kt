package io.sonora.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.TranslucentBarButtonSize
import io.sonora.core.designsystem.component.TranslucentBarInset
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.layout.TopChromeSpace

/** A bar's height: its translucent [button] with the same [inset] above and below. */
internal fun translucentBarHeight(button: Dp, inset: Dp): Dp = button + inset * 2

/** Room a side's buttons take from a bar's title: their [width] and a [gap], none when the side is empty. */
private fun clearance(width: Int, gap: Int): Int = if (width == 0) 0 else width + gap

/**
 * The widest a title in a [barWidth] bar may be: all the room between its [leading] and [trailing] buttons, keeping
 * [gap] clear of each. A side with no button needs no gap.
 */
internal fun titleRoom(barWidth: Int, leading: Int, trailing: Int, gap: Int): Int =
    (barWidth - clearance(leading, gap) - clearance(trailing, gap)).coerceAtLeast(0)

/**
 * Where a [title] wide title starts in a [barWidth] bar: centred while that keeps it clear of both sides' buttons, else
 * moved toward the narrower side just enough, so a long title uses the free room before it is cut.
 */
internal fun titleStart(barWidth: Int, leading: Int, trailing: Int, gap: Int, title: Int): Int {
    val first = clearance(leading, gap)
    val last = barWidth - clearance(trailing, gap) - title
    return ((barWidth - title) / 2).coerceAtMost(last).coerceAtLeast(first)
}

/** Height of a plain page's floating top bar, which its content leaves free at its top ([TranslucentTopBarSpace]). */
internal val TranslucentTopBarHeight: Dp = translucentBarHeight(TranslucentBarButtonSize, TranslucentBarInset)

/** The gap a bar's centred title keeps from its buttons. */
private val TitleGap = 8.dp

/** The solid part of a [TranslucentBarPage]'s scroll edge: the bar's text and buttons, not its bottom inset. */
private val ScrollEdgeSolid = TranslucentTopBarHeight - TranslucentBarInset * 2

/** The height of a [TranslucentBarPage]'s scroll edge: it ends at the bar's bottom edge, where [TranslucentTopBarSpace] ends. */
internal val ScrollEdgeHeight: Dp = TranslucentTopBarHeight

/** How opaque the scroll edge is [y] below the top of the page: solid to [ScrollEdgeSolid], gone at [ScrollEdgeHeight]. */
internal fun scrollEdgeAlpha(y: Dp): Float {
    val fade = ScrollEdgeHeight - ScrollEdgeSolid
    if (fade <= 0.dp) return if (y <= ScrollEdgeSolid) 1f else 0f
    return (1f - (y - ScrollEdgeSolid) / fade).coerceIn(0f, 1f)
}

/**
 * A plain (untinted) page whose top bar floats over its scrolling content as translucent buttons, as on iOS 26:
 * [content] is recorded, over the theme background, as the backdrop the bar's translucency samples, and starts with a
 * [TranslucentTopBarSpace] so its first item clears the bar until it scrolls under it. [bar] is usually a
 * [TranslucentTopBar].
 * [scrollEdge] fades the content scrolled under the bar into the background (iOS 26's scroll edge effect), for a bar
 * whose plain text (a title, a count) must stay readable. It ends at the bar's bottom edge ([ScrollEdgeHeight]), so
 * the first item at scroll 0, which starts there, is not veiled; only rows scrolled under the bar are.
 * [overlay] holds other translucent surfaces floating over the content (e.g. the [SelectionBar]), placed in the page's
 * box and sampling the same backdrop; translucency composed inside [content] would sample itself and draw flat.
 */
@Composable
internal fun TranslucentBarPage(
    bar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scrollEdge: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val translucent = rememberTranslucentBackdrop()
    val background = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().translucentBackdropSource(translucent).background(background)) {
            content()
            // Recorded with the content, so the bar's translucency samples the faded rows too.
            if (scrollEdge) ScrollEdge(background)
        }
        // Only the bar and the overlay sample this backdrop: translucency inside the content would sample itself.
        CompositionLocalProvider(LocalTranslucentBackdrop provides translucent) {
            Box(Modifier.fillMaxWidth().height(TranslucentTopBarHeight)) { bar() }
            overlay()
        }
    }
}

/** The background over the top of the content: solid behind the bar's text and buttons, then fading out. */
@Composable
private fun ScrollEdge(background: Color) {
    val stops = listOf(0.dp, ScrollEdgeSolid, ScrollEdgeHeight)
        .map { y -> y / ScrollEdgeHeight to background.copy(alpha = scrollEdgeAlpha(y)) }
        .toTypedArray()
    Box(
        Modifier
            .fillMaxWidth()
            .height(ScrollEdgeHeight)
            .background(Brush.verticalGradient(*stops)),
    )
}

/**
 * The top of a [TranslucentBarPage]'s content, under its floating bar, and under the shell's top pill too on a large screen
 * whose sidebar is hidden (the bar's buttons sit beside the pill; the title below must clear it).
 */
@Composable
internal fun TranslucentTopBarSpace() {
    TopChromeSpace(own = TranslucentTopBarHeight)
}

/**
 * A floating top bar: the back button when [onBack] is set (TalkBack reads [backLabel]), else [leading]; [trailing]
 * buttons at the end; and an optional [title] centred in the bar, never under either side's buttons: a title too wide
 * to stay centred moves toward the narrower side ([titleStart]) and is cut only past the room between them.
 */
@Composable
internal fun TranslucentTopBar(
    modifier: Modifier = Modifier,
    backLabel: String = "",
    onBack: (() -> Unit)? = null,
    leading: @Composable RowScope.() -> Unit = {},
    title: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Layout(
        contents = listOf(
            {
                if (onBack != null) {
                    BackHeader(backLabel, onBack)
                } else {
                    Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
                }
            },
            { title?.invoke() },
            {
                Row(
                    Modifier.padding(end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            },
        ),
        modifier = modifier.fillMaxSize(),
    ) { (leadingMeasurables, titleMeasurables, trailingMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = leadingMeasurables.map { it.measure(loose) }
        val end = trailingMeasurables.map { it.measure(loose) }
        val leadingWidth = start.maxOfOrNull { it.width } ?: 0
        val trailingWidth = end.maxOfOrNull { it.width } ?: 0
        val gap = TitleGap.roundToPx()
        val titleWidth = titleRoom(constraints.maxWidth, leadingWidth, trailingWidth, gap)
        val centre = titleMeasurables.map { it.measure(Constraints(maxWidth = titleWidth, maxHeight = loose.maxHeight)) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            start.forEach { it.place(0, (height - it.height) / 2) }
            centre.forEach { it.place(titleStart(width, leadingWidth, trailingWidth, gap, it.width), (height - it.height) / 2) }
            end.forEach { it.place(width - it.width, (height - it.height) / 2) }
        }
    }
}
