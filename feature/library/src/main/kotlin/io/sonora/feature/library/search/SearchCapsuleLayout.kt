package io.sonora.feature.library.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The search capsule's height, in dp: the floating chrome's scale, above the 44 dp touch target. */
internal const val SEARCH_CAPSULE_HEIGHT = 48f

/** Space between the capsule and the keyboard's top edge, in dp. */
internal const val SEARCH_CAPSULE_KEYBOARD_GAP = 8f

/** Space between the capsule's top and the end of the scrolling results, in dp. */
internal const val SEARCH_CAPSULE_CONTENT_GAP = 12f

/**
 * Distance (dp) from the screen's bottom edge to the capsule's bottom edge. It floats right above the bottom chrome,
 * whose [chromePadding] (`BottomChromePadding`) already holds the gap over the mini player and tab bar, and rises with
 * the keyboard, [ime] being the keyboard's top edge measured from the screen's bottom (0 while it is hidden).
 */
internal fun searchCapsuleBottom(chromePadding: Float, ime: Float): Float =
    maxOf(chromePadding, ime + SEARCH_CAPSULE_KEYBOARD_GAP)

/** Bottom padding (dp) of the results, so they scroll under the capsule and their last row ends clear of it. */
internal fun searchContentPadding(chromePadding: Float, ime: Float): Float =
    searchCapsuleBottom(chromePadding, ime) + SEARCH_CAPSULE_HEIGHT + SEARCH_CAPSULE_CONTENT_GAP

/**
 * Content padding whose bottom is read at layout time, like `WindowInsets.asPaddingValues()`: the keyboard and the
 * chrome's minimise animation move the list's end without recomposing it.
 */
@Stable
internal class LayoutBottomPadding(private val bottom: Density.() -> Dp, private val density: Density) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateTopPadding(): Dp = 0.dp

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateBottomPadding(): Dp = density.bottom()
}
