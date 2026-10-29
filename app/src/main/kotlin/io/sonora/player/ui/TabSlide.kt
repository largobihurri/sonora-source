package io.sonora.player.ui

/**
 * Horizontal offset (share of the width) of tab [index]'s screen while the tab strip sits at [position] (a tab index,
 * fractional mid-switch). Screens to the right of the strip position sit to the right, mirrored in right-to-left
 * layouts where later tabs lie to the left. `+ 0f` turns a negative zero into 0f.
 */
fun tabSlideOffset(index: Int, position: Float, rtl: Boolean): Float =
    (index - position) * (if (rtl) -1f else 1f) + 0f
