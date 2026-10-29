package io.sonora.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.sonora.core.designsystem.theme.animatedBackdropColor

/** Where the strip under the sidebar starts and how wide it is, in px. */
internal data class StripSpan(val start: Float, val width: Float)

/**
 * The strip of a [windowPx] wide window that the pages leave free for the sidebar, [insetPx] wide at the start edge (the
 * right one in right-to-left layouts), never past the window.
 */
internal fun underSidebarStrip(insetPx: Float, windowPx: Float, rtl: Boolean): StripSpan {
    val width = insetPx.coerceIn(0f, windowPx.coerceAtLeast(0f))
    return StripSpan(start = if (rtl) windowPx - width else 0f, width = width)
}

/**
 * The room the pages leave for the sidebar, painted with the page's own background: the page on screen's tint
 * ([io.sonora.core.designsystem.theme.ChromeTint.backdrop]) or the theme's background on a plain page, so the sidebar's
 * translucent surface floats over the page, as on iPadOS, and the window's colour never shows there (not even while the
 * sidebar slides in over pages that already took their final width). Drawn in the surface's source layer, so the
 * surface samples it.
 */
@Composable
internal fun UnderSidebarTint(insetPx: Float, modifier: Modifier = Modifier) {
    val fill = animatedBackdropColor(MaterialTheme.colorScheme.background)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier.fillMaxSize().drawBehind {
            val strip = underSidebarStrip(insetPx, size.width, rtl)
            if (strip.width > 0f) drawRect(fill.value, Offset(strip.start, 0f), Size(strip.width, size.height))
        },
    )
}
