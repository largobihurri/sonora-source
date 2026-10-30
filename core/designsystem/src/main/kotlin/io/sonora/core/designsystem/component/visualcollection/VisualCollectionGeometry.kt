package io.sonora.core.designsystem.component.visualcollection

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sign

/** Where one cover is drawn, relative to the centre slot (pixels, degrees); higher [zIndex] draws on top. */
@Immutable
data class CoverTransform(
    val translationX: Float,
    val translationY: Float,
    val rotationY: Float,
    val scale: Float,
    val zIndex: Float,
)

/** Where the centre cover sits in its window: its side and the pixel row of its top edge. */
@Immutable
data class VisualCollectionFrame(val coverSize: Float, val coverTop: Float)

/**
 * The original iPhone visual collection layout for square covers of [coverSize] pixels. A cover's offset is its index minus
 * the strip's position, so it moves continuously: within one step of the centre it slides [centreGap] out while it
 * turns to [sideAngle] and shrinks to [sideScale]; farther covers stack [sideStep] apart, like books on a shelf.
 * Pure, so it is unit-tested.
 */
@Immutable
data class VisualCollectionGeometry(
    val coverSize: Float,
    val centreGap: Float = coverSize * 0.66f,
    val sideStep: Float = coverSize * 0.17f,
    val sideAngle: Float = 68f,
    val sideScale: Float = 0.8f,
) {
    /** Finger travel that moves the strip by one cover: between the centre gap and the side step. */
    val dragPxPerItem: Float get() = (centreGap + sideStep) / 2f

    fun transform(offset: Float): CoverTransform {
        val distance = abs(offset)
        val side = sign(offset)
        val turn = min(distance, 1f)
        val x = if (distance <= 1f) offset * centreGap else side * (centreGap + (distance - 1f) * sideStep)
        val scale = 1f - (1f - sideScale) * turn
        return CoverTransform(
            translationX = x,
            translationY = coverSize * (1f - scale) / 2f,
            // Negative turns a right-hand cover's face to the left: each side faces the centre. `0f -` keeps the
            // centre at +0 (not -0).
            rotationY = 0f - side * sideAngle * turn,
            scale = scale,
            zIndex = 0f - distance,
        )
    }

    /** Half the on-screen width of the cover at [offset], ignoring perspective. */
    fun projectedHalfWidth(offset: Float): Float {
        val t = transform(offset)
        return coverSize / 2f * t.scale * abs(cos(Math.toRadians(t.rotationY.toDouble())).toFloat())
    }

    /**
     * The cover under a tap [x] pixels right of the centre slot's middle, the strip at [position]: the frontmost one
     * among the [window] whose projected width covers the tap, or `null`.
     */
    fun hitTest(x: Float, position: Float, window: IntRange): Int? = window
        .filter { index ->
            val offset = index - position
            abs(x - transform(offset).translationX) <= projectedHalfWidth(offset)
        }
        .minByOrNull { abs(it - position) }

    companion object {
        /** Covers drawn either side of the centre; farther ones are hidden behind the stack anyway. */
        const val WINDOW_RADIUS = 8

        private const val COVER_HEIGHT_FRACTION = 0.70f
        private const val COVER_WIDTH_FRACTION = 0.47f

        /** A phone's centre cover starts this far down its screen (a fraction of the height). */
        const val COVER_TOP_FRACTION = 0.045f

        /** How far the reflection reaches below a cover, as a fraction of its side. */
        const val REFLECTION_FRACTION = 0.33f

        /** The centre cover's side on a large upright window, as a fraction of its width (the sides still fit). */
        private const val LARGE_UPRIGHT_WIDTH_FRACTION = 0.58f

        private const val DEFAULT_CAPTION_RESERVE = 0f
        private const val BACK_HEIGHT_FRACTION = 0.9f
        private const val BACK_WIDTH_FRACTION = 0.6f

        /** The centre cover's side on a [screenWidth] x [screenHeight] screen: as tall as fits, never too wide. */
        fun coverSize(screenWidth: Float, screenHeight: Float): Float =
            min(screenHeight * COVER_HEIGHT_FRACTION, screenWidth * COVER_WIDTH_FRACTION)

        /**
         * Where the centre cover goes in a [screenWidth] x [screenHeight] window. A phone, or a large window turned
         * sideways ([large] but wider than tall), keeps the original place: the cover as tall as fits, near the top.
         * A [large] window upright (a tablet in portrait) is far taller than the strip: its covers are bigger
         * ([LARGE_UPRIGHT_WIDTH_FRACTION] of the width, never past 70 percent of the height) and the covers with their
         * reflection are centred in the room above the [captionReserve] kept at the bottom for the title and subtitle.
         */
        fun frame(screenWidth: Float, screenHeight: Float, large: Boolean, captionReserve: Float = DEFAULT_CAPTION_RESERVE): VisualCollectionFrame {
            if (!large || screenHeight <= screenWidth) {
                return VisualCollectionFrame(coverSize(screenWidth, screenHeight), screenHeight * COVER_TOP_FRACTION)
            }
            val cover = min(screenWidth * LARGE_UPRIGHT_WIDTH_FRACTION, screenHeight * COVER_HEIGHT_FRACTION)
            val block = cover * (1f + REFLECTION_FRACTION)
            val top = ((screenHeight - captionReserve - block) / 2f).coerceAtLeast(screenHeight * COVER_TOP_FRACTION)
            return VisualCollectionFrame(cover, top)
        }

        /** The side of a flipped cover's back, a larger card in the middle of a [screenWidth] x [screenHeight] screen. */
        fun backSize(screenWidth: Float, screenHeight: Float): Float =
            min(screenHeight * BACK_HEIGHT_FRACTION, screenWidth * BACK_WIDTH_FRACTION)

        /**
         * The width of the card on show [flip] of the way (0 to 1) through turning the centre cover over: the cover
         * grows into its back as it turns, so the width moves smoothly from the [cover]'s side (by default
         * [coverSize], or what [frame] chose) to [backSize], never smaller than the cover.
         */
        fun cardWidth(screenWidth: Float, screenHeight: Float, flip: Float, cover: Float = coverSize(screenWidth, screenHeight)): Float {
            val back = maxOf(backSize(screenWidth, screenHeight), cover)
            return cover + (back - cover) * flip.coerceIn(0f, 1f)
        }

        /** Of a [left] and a [right] inset, the one on the end side (the right, or the left when [rtl]). */
        fun endInset(left: Float, right: Float, rtl: Boolean): Float = if (rtl) left else right

        /**
         * The top of the card on show [flip] of the way (0 to 1) through turning the centre cover over: the [cover] at
         * [coverTop] rises to the middle of a [screenHeight] screen as it grows into its [back].
         */
        fun cardTop(screenHeight: Float, coverTop: Float, cover: Float, back: Float, flip: Float): Float {
            val p = flip.coerceIn(0f, 1f)
            val centre = coverTop + cover / 2f + (screenHeight / 2f - (coverTop + cover / 2f)) * p
            return centre - (cover + (back - cover) * p) / 2f
        }

        /**
         * The widest a top-end caption may grow on a [screenWidth] screen with a [cardWidth] card in the middle: the
         * room from the screen's edge, less its [edge] padding and the [cutout] on its own side, to [gap] short of the
         * card, at most [cap] and at least [floor]. A card whose [cardTop] is at least [gap] below the caption's
         * [captionBottom] (a tablet upright) leaves the whole width to the caption, so it takes its [cap].
         */
        fun captionMaxWidth(
            screenWidth: Float,
            cardWidth: Float,
            edge: Float,
            cutout: Float,
            gap: Float,
            floor: Float,
            cap: Float,
            captionBottom: Float = Float.POSITIVE_INFINITY,
            cardTop: Float = 0f,
        ): Float = if (cardTop >= captionBottom + gap) {
            cap.coerceAtMost(screenWidth - 2f * edge - cutout).coerceAtLeast(floor)
        } else {
            ((screenWidth - cardWidth) / 2f - edge - cutout - gap).coerceIn(floor, cap)
        }

        /** Indices worth composing around [position], [radius] either side, within a list of [count]. */
        fun window(position: Float, count: Int, radius: Int = WINDOW_RADIUS): IntRange {
            if (count <= 0) return IntRange.EMPTY
            val first = (floor(position).toInt() - radius).coerceAtLeast(0)
            val last = (ceil(position).toInt() + radius).coerceAtMost(count - 1)
            return first..last
        }
    }
}
