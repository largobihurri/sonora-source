package io.sonora.core.designsystem.component.visualcollection

import io.sonora.core.designsystem.theme.ColorMath
import kotlin.math.roundToInt

/**
 * Colour rules of the visual collection background: the centre cover, blurred, drawn at [ARTWORK_ALPHA] over its palette
 * colour, under a scrim of the [darkened] colour just heavy enough ([scrimAlpha]) for white titles to keep
 * [TEXT_CONTRAST] even where the artwork is white. Pure, so it is unit-tested.
 */
object VisualCollectionBackdrop {
    const val ARTWORK_ALPHA = 0.6f
    const val MIN_SCRIM = 0.35f
    const val TEXT_CONTRAST = 4.5
    const val MAX_LIGHTNESS = 0.22

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val DARKENING = 0.55

    /** [argb] in the same hue, much darker: Oklch lightness scaled down and capped at [MAX_LIGHTNESS]. */
    fun darkened(argb: Int): Int {
        val color = ColorMath.toOklch(argb)
        return ColorMath.fromOklch(color.copy(l = minOf(color.l * DARKENING, MAX_LIGHTNESS)))
    }

    /**
     * The lightest scrim of [scrim] (at least [MIN_SCRIM]) under which white text keeps [TEXT_CONTRAST] over the
     * worst case: a white artwork pixel drawn at [ARTWORK_ALPHA] over [base].
     */
    fun scrimAlpha(base: Int, scrim: Int): Float {
        val worst = blend(base, WHITE, ARTWORK_ALPHA)
        fun readable(alpha: Float) = ColorMath.contrastRatio(WHITE, blend(worst, scrim, alpha)) >= TEXT_CONTRAST
        if (readable(MIN_SCRIM)) return MIN_SCRIM
        var low = MIN_SCRIM
        var high = 1f
        repeat(20) {
            val mid = (low + high) / 2f
            if (readable(mid)) high = mid else low = mid
        }
        return high
    }

    /** [over] drawn at [alpha] on opaque [under], per sRGB channel, as opaque ARGB. */
    fun blend(under: Int, over: Int, alpha: Float): Int {
        fun channel(shift: Int): Int {
            val below = (under shr shift) and 0xFF
            val above = (over shr shift) and 0xFF
            return (below + (above - below) * alpha).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
