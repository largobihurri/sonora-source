package io.sonora.core.designsystem.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.min

/** The window's width bucket, on the Material breakpoints: under 600 dp, 600 up to 840 dp, and 840 dp or more. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * What the app's window is, in coarse classes (not exact sizes, so a window dragged to a new width recomposes its readers
 * only when a class changes). Taken from the window itself, not the screen, so a split screen or a freeform window
 * resized by the user changes it.
 *
 * - [widthClass] is the width bucket. A phone held sideways can reach [WidthClass.MEDIUM] or [WidthClass.EXPANDED] too,
 *   so gate a tablet arrangement on [isLarge], and use [widthClass] to choose among the arrangements of a large window.
 * - [isLarge]: the smallest side is at least 600 dp, so the window is a tablet's (or an unfolded foldable's) in either
 *   orientation. Phones are never large. Everything that exists only for tablets is gated on it.
 * - [isLandscape]: wider than tall.
 *
 * Read it with [LocalWindowClass]; [of] is the pure rule.
 */
@Immutable
data class WindowClass(
    val widthClass: WidthClass,
    val isLarge: Boolean,
    val isLandscape: Boolean,
) {
    companion object {
        /** The smallest width of a [WidthClass.MEDIUM] window. */
        const val MEDIUM_MIN_WIDTH_DP = 600

        /** The smallest width of a [WidthClass.EXPANDED] window. */
        const val EXPANDED_MIN_WIDTH_DP = 840

        /** The smallest side of a [large][isLarge] window. */
        const val LARGE_MIN_SIDE_DP = 600

        /** A phone held upright: compact, not large, portrait. What [LocalWindowClass] reads outside [ProvideWindowClass]. */
        val Phone = WindowClass(WidthClass.COMPACT, isLarge = false, isLandscape = false)

        /** The class of a window [widthDp] wide and [heightDp] tall (negative sizes count as 0). */
        fun of(widthDp: Int, heightDp: Int): WindowClass {
            val width = widthDp.coerceAtLeast(0)
            val height = heightDp.coerceAtLeast(0)
            val widthClass = when {
                width >= EXPANDED_MIN_WIDTH_DP -> WidthClass.EXPANDED
                width >= MEDIUM_MIN_WIDTH_DP -> WidthClass.MEDIUM
                else -> WidthClass.COMPACT
            }
            return WindowClass(widthClass, isLarge = min(width, height) >= LARGE_MIN_SIDE_DP, isLandscape = width > height)
        }
    }
}

/**
 * The window's class, provided once at the root by [ProvideWindowClass]. Structurally compared, so readers recompose only
 * when the class itself changes, not on every step of a window being resized.
 */
val LocalWindowClass = compositionLocalOf { WindowClass.Phone }

/**
 * Provides [LocalWindowClass] to [content], taken from the window's configuration. The Activity handles size changes
 * itself (manifest configChanges), so the configuration updates on a rotation, a split screen or a resize without
 * recreating anything, and it is known from the first composition (no phone-sized first frame on a tablet).
 */
@Composable
fun ProvideWindowClass(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val windowClass = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        WindowClass.of(configuration.screenWidthDp, configuration.screenHeightDp)
    }
    CompositionLocalProvider(LocalWindowClass provides windowClass, content = content)
}
