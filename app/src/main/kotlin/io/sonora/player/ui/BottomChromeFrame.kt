package io.sonora.player.ui

/** Space (dp) between the top of the chrome and the end of scrolling content. */
internal const val ChromeGap = 12f

/** One chrome element, in dp: [x] from the start edge, [bottom] from the screen's bottom edge. */
internal data class ChromeRect(val x: Float, val bottom: Float, val width: Float, val height: Float) {
    val top: Float get() = bottom + height
}

/** Where the tab bar and the mini player (if any) sit at one point of the morph. */
internal data class BottomChromeFrame(val tabBar: ChromeRect, val miniPlayer: ChromeRect?) {
    /** Distance from the screen's bottom edge to the chrome's top, navigation bar included. */
    val height: Float get() = maxOf(tabBar.top, miniPlayer?.top ?: 0f)

    /** Bottom padding that keeps the last item of scrolling content clear of the chrome. */
    val contentPadding: Float get() = height + ChromeGap
}

/** Expanded: tab bar cell row plus its inset. */
private const val TabBarHeight = 59f
private const val TabBarMargin = 8f
internal const val MiniPlayerHeight = 60f
private const val MiniPlayerMaxWidth = 520f
internal const val SideMargin = 16f

/** Collapsed: one low row, a round tab pill then the mini player. */
private const val CompactHeight = 52f
private const val CompactMargin = 4f
private const val CompactGap = 8f

/**
 * The chrome at [collapse] (0 expanded, 1 collapsed; spring overshoot is clamped) on a [screenWidth] wide screen above a
 * [navigationBar] inset, all in dp. Expanded, the mini player floats above the centred tab bar ([tabBarWidth] wide);
 * collapsed, the tab bar is a round pill at the start of a lower row and the mini player takes the rest of it. Every
 * edge moves linearly between the two, so the morph and the content padding follow the same animation.
 */
internal fun bottomChromeFrame(
    collapse: Float,
    screenWidth: Float,
    navigationBar: Float,
    tabBarWidth: Float,
    hasMiniPlayer: Boolean,
): BottomChromeFrame {
    val t = collapse.coerceIn(0f, 1f)
    val compactBottom = navigationBar + CompactMargin

    val tabBarBottom = navigationBar + TabBarMargin
    val tabBar = lerp(
        ChromeRect((screenWidth - tabBarWidth) / 2f, tabBarBottom, tabBarWidth, TabBarHeight),
        ChromeRect(SideMargin, compactBottom, CompactHeight, CompactHeight),
        t,
    )
    val miniPlayer = if (!hasMiniPlayer) {
        null
    } else {
        val width = minOf(screenWidth - 2 * SideMargin, MiniPlayerMaxWidth)
        val compactX = SideMargin + CompactHeight + CompactGap
        lerp(
            ChromeRect((screenWidth - width) / 2f, tabBarBottom + TabBarHeight + TabBarMargin, width, MiniPlayerHeight),
            ChromeRect(compactX, compactBottom, (screenWidth - compactX - SideMargin).coerceAtLeast(0f), CompactHeight),
            t,
        )
    }
    return BottomChromeFrame(tabBar, miniPlayer)
}

private fun lerp(from: ChromeRect, to: ChromeRect, t: Float) = ChromeRect(
    x = lerp(from.x, to.x, t),
    bottom = lerp(from.bottom, to.bottom, t),
    width = lerp(from.width, to.width, t),
    height = lerp(from.height, to.height, t),
)

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
