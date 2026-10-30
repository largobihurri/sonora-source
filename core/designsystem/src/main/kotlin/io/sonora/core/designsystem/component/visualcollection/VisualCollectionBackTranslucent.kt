package io.sonora.core.designsystem.component.visualcollection

import io.sonora.core.designsystem.translucent.TranslucentContrast
import io.sonora.core.designsystem.translucent.TranslucentMaterial
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.theme.ColorMath

/**
 * Rules of the translucent back of a flipped visual collection card, free of Android so they are unit-tested. The back is
 * tinted with the album's own colour (its [io.sonora.core.designsystem.theme.ArtworkPalette.background], the album
 * page tint): lightly at the rim, where the refraction shows, and more strongly behind the header and track list,
 * so they stay readable over busy covers. While the card turns, an opaque [veil] (today's panel) hides the translucent
 * back, whose sampling cannot follow a 3D pose; it lifts once the card has settled flat.
 */
internal object VisualCollectionBackTranslucent {
    /** The design tint at the rim, raised when the label's contrast needs more. */
    const val RIM_ALPHA = 0.35f

    /** The design tint behind the header and list, raised when their contrast needs more. */
    const val BODY_ALPHA = 0.55f

    /** Titles behind the list keep WCAG AAA, over the brightest and darkest backdrop the translucent back can show. */
    const val BODY_CONTRAST = 7.0

    /** The back's secondary text (numbers, durations, subtitles): white at this alpha; it keeps WCAG AA. */
    const val SECONDARY_TEXT_ALPHA = 0.6f

    /** Closing, the veil is fully back within this much of the turn (from flat), before the pose bends far. */
    const val CLOSE_FADE = 0.15f

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** The album [color] over the back: [rimAlpha] over the whole surface, [bodyAlpha] behind its content. */
    data class Tint(val color: Int, val rimAlpha: Float, val bodyAlpha: Float) {
        /** The alpha of a second layer of [color], drawn over the rim tint, that adds up to [bodyAlpha]. */
        val bodyOverlayAlpha: Float
            get() = if (rimAlpha >= 1f) 0f else (1f - (1f - bodyAlpha) / (1f - rimAlpha)).coerceIn(0f, 1f)
    }

    /**
     * The tint of [albumColor] on [translucent]: each alpha is its design value or, when contrast needs more, the thinnest
     * alpha (in 1/255 steps) that reaches it; behind the content never thinner than at the rim.
     */
    fun tint(albumColor: Int, translucent: TranslucentMaterial): Tint {
        val rim = maxOf(RIM_ALPHA, minimumAlpha(translucent, albumColor) { TranslucentContrast.isReadable(WHITE, it) })
        val body = maxOf(rim, BODY_ALPHA, minimumAlpha(translucent, albumColor, ::bodyReadable))
        return Tint(albumColor, rim, body)
    }

    /**
     * The two extremes the back can look like with [tint] at [alpha] over [translucent]: that material's own tint over
     * the darkest and the lightest backdrop it shows, under the album tint.
     */
    fun shown(translucent: TranslucentMaterial, tint: Int, alpha: Float): List<Int> =
        TranslucentContrast.worstBackgrounds(translucent.tint, translucent.tintAlpha, translucent.range).map { TranslucentContrast.composite(tint, alpha, it) }

    /**
     * Whether the back is translucent: only when its surface samples the visual collection ([hasBackdrop], a sampling tier) and
     * high contrast is off. Otherwise it keeps today's opaque panel; flat translucency would be a thinner, less
     * readable fill.
     */
    fun showsTranslucent(policy: TranslucentPolicy, hasBackdrop: Boolean): Boolean =
        hasBackdrop && policy.samplesBackdrop && !policy.highContrast

    /**
     * How opaque the veil is: 1 while the card turns open ([settled] is the fade-in that starts once
     * the turn has ended at [flip] 1), and back to 1 within the first [CLOSE_FADE] of the turn closing.
     */
    fun veil(settled: Float, flip: Float): Float {
        val turning = ((1f - flip) / CLOSE_FADE).coerceIn(0f, 1f)
        return maxOf(1f - settled.coerceIn(0f, 1f), turning)
    }

    private fun bodyReadable(shown: List<Int>): Boolean = shown.all { background ->
        ColorMath.contrastRatio(WHITE, background) >= BODY_CONTRAST &&
            ColorMath.contrastRatio(TranslucentContrast.composite(WHITE, SECONDARY_TEXT_ALPHA, background), background) >= ColorMath.AA_CONTRAST
    }

    /** The thinnest alpha of [tint] on [translucent] that [readable] accepts; 1 (the opaque album colour) as a last resort. */
    private fun minimumAlpha(translucent: TranslucentMaterial, tint: Int, readable: (List<Int>) -> Boolean): Float {
        for (step in 0..255) {
            val alpha = step / 255f
            if (readable(shown(translucent, tint, alpha))) return alpha
        }
        return 1f
    }
}
