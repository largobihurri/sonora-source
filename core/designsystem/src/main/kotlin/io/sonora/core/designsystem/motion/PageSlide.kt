package io.sonora.core.designsystem.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Where the tab page holding this content is on the app's sliding tab strip, as a fraction of the page width: 0 while
 * it is settled, towards -1 as it slides out to the left, towards 1 as it slides in from the right (mirrored in a
 * right-to-left layout). A function so it is read in the draw phase only: the slide redraws, it never recomposes.
 * Outside a tab strip it is always 0.
 */
val LocalPageSlide = staticCompositionLocalOf<() -> Float> { NotSliding }

private val NotSliding: () -> Float = { 0f }

/**
 * How much of each side of a page's own background fades out while its tab slides, in pixels: the edge that meets the
 * next tab dissolves into it, so pages with their own colour pass as one soft plane instead of showing a vertical cut.
 */
@Immutable
data class PageEdgeFade(val left: Float, val right: Float) {
    companion object {
        val None = PageEdgeFade(0f, 0f)

        /** The widest fade, as a fraction of the page width. */
        const val MAX_FRACTION = 0.35f

        /** The slide over which the fade grows from nothing to [MAX_FRACTION], so the first frames do not jump. */
        const val RAMP = 0.15f

        fun of(offset: Float, width: Float): PageEdgeFade {
            if (offset == 0f || width <= 0f) return None
            val fade = width * MAX_FRACTION * (abs(offset) / RAMP).coerceAtMost(1f)
            return if (offset < 0f) PageEdgeFade(left = 0f, right = fade) else PageEdgeFade(left = fade, right = 0f)
        }
    }
}

/**
 * Fills the page with [color], as `background` does, but fades the edge that meets the neighbouring tab while the tab
 * strip slides ([LocalPageSlide]). A settled page is a plain solid fill.
 */
@Composable
fun Modifier.slidingPageBackground(color: Color): Modifier {
    val slide = LocalPageSlide.current
    return drawBehind {
        val fade = PageEdgeFade.of(slide(), size.width)
        if (fade == PageEdgeFade.None) {
            drawRect(color)
        } else {
            val clear = color.copy(alpha = 0f)
            val w = size.width
            drawRect(
                Brush.horizontalGradient(
                    0f to (if (fade.left > 0f) clear else color),
                    (fade.left / w) to color,
                    (1f - fade.right / w) to color,
                    1f to (if (fade.right > 0f) clear else color),
                ),
            )
        }
    }
}
