package io.sonora.core.designsystem.translucent

import io.sonora.core.designsystem.theme.ColorMath
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Contrast of content drawn on a translucent surface, free of Android so it is unit-tested. Translucency shows an
 * unknown backdrop through a tint; the backdrop is any grey between two levels ([BackdropRange], after the surface's
 * own colour filter), so text stays readable when it reaches the ratio over the tint composited on both extremes, and
 * its own luminance is not crossed by some backdrop in between.
 */
object TranslucentContrast {

    /**
     * The sRGB grey levels, 0..1, the surface can show behind its tint. An [eased] range (liquid translucency's light shader)
     * leaves every level the label does not need changed as it is and eases only the rest into the range ([map]); a
     * plain one (a colour matrix) scales every level into it.
     */
    data class BackdropRange(val darkest: Float, val lightest: Float, val eased: Boolean = false) {

        /**
         * Where the surface shows one sRGB channel at [level] (0..1): always within [darkest]..[lightest], in order. Eased,
         * a level up to [KNEE] of the way to [lightest] (from black) or to [darkest] (from white) is left as it is, and
         * the rest is eased into the range with no kink ([TranslucentShaders.LIGHT_SHADER] runs the same formula).
         */
        fun map(level: Float): Float {
            val x = level.coerceIn(0f, 1f)
            if (!eased) return darkest + (lightest - darkest) * x
            val top = easeInto(x, lightest)
            return (1f - easeInto(1f - top, 1f - darkest)).coerceIn(darkest, lightest)
        }

        companion object {
            /** Anything from black to white: what flat translucency sees, unfiltered. */
            val Full = BackdropRange(0f, 1f)

            /** The share of the way to an eased range's bound that the translucency leaves untouched. */
            const val KNEE = 0.6f

            /**
             * [x] (0..1) eased into 0..[bound]: unchanged up to [KNEE] of [bound], then bent by `1 - (1 - t)^n` so the
             * slope starts at 1 and white lands exactly on [bound].
             */
            private fun easeInto(x: Float, bound: Float): Float {
                if (bound >= 1f) return x
                val knee = KNEE * bound
                if (x <= knee) return x
                val t = (x - knee) / (1f - knee)
                val n = (1f - knee) / (bound - knee)
                return knee + (bound - knee) * (1f - (1f - t).pow(n))
            }
        }
    }

    /** [tint] at [alpha] drawn over the opaque [backdrop] (source-over in sRGB, as Android blends). */
    fun composite(tint: Int, alpha: Float, backdrop: Int): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val t = (tint shr shift) and 0xFF
            val b = (backdrop shr shift) and 0xFF
            return (t * a + b * (1 - a)).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** The two extremes the surface can look like: [tint] at [alpha] over the darkest and the lightest backdrop. */
    fun worstBackgrounds(tint: Int, alpha: Float, range: BackdropRange): List<Int> =
        listOf(composite(tint, alpha, darkestGrey(range)), composite(tint, alpha, lightestGrey(range)))

    /**
     * Whether [content] reaches [minRatio] against every one of [backgrounds] (the extremes of a continuous range) and
     * no background in between can match its luminance.
     */
    fun isReadable(content: Int, backgrounds: List<Int>, minRatio: Double = ColorMath.AA_CONTRAST): Boolean {
        if (backgrounds.any { ColorMath.contrastRatio(content, it) < minRatio }) return false
        val luminance = ColorMath.relativeLuminance(content)
        val levels = backgrounds.map(ColorMath::relativeLuminance)
        return luminance >= levels.max() || luminance <= levels.min()
    }

    /**
     * The thinnest [tint] alpha, in 1/255 steps, that keeps [content] readable over every backdrop in [range]; 1 when
     * even the opaque tint is not enough (the caller then draws an opaque surface).
     */
    fun minimumAlpha(tint: Int, content: Int, range: BackdropRange, minRatio: Double = ColorMath.AA_CONTRAST): Float {
        for (step in 0..255) {
            val alpha = step / 255f
            if (isReadable(content, worstBackgrounds(tint, alpha, range), minRatio)) return alpha
        }
        return 1f
    }

    /**
     * The thickest [tint] alpha, in 1/255 steps, that keeps [content] readable over every backdrop in [range]; `null`
     * when even no tint at all is readable (a different tint is needed).
     */
    fun maximumAlpha(tint: Int, content: Int, range: BackdropRange, minRatio: Double = ColorMath.AA_CONTRAST): Float? {
        for (step in 255 downTo 0) {
            val alpha = step / 255f
            if (isReadable(content, worstBackgrounds(tint, alpha, range), minRatio)) return alpha
        }
        return null
    }

    /**
     * [color] (an accent, say) made readable on the surface: unchanged when it already is, otherwise moved in lightness
     * with its hue kept ([ColorMath.ensureContrast]); white or black as a last resort.
     */
    fun readableOn(color: Int, tint: Int, alpha: Float, range: BackdropRange, minRatio: Double = ColorMath.AA_CONTRAST): Int {
        val backgrounds = worstBackgrounds(tint, alpha, range)
        val adjusted = ColorMath.ensureContrast(color, backgrounds, minRatio)
        if (isReadable(adjusted, backgrounds, minRatio)) return adjusted
        return listOf(WHITE, BLACK).maxBy { candidate -> backgrounds.minOf { ColorMath.contrastRatio(candidate, it) } }
    }

    /**
     * The two extremes the surface can look like where a white [sheen] (its light: a highlight band, a caustic) may add
     * up to that alpha: the darkest backdrop unlit, and the lightest one fully lit. Sampling translucency lights what it shows,
     * under its tint; flat translucency draws the light [overTint].
     */
    fun sheenBackgrounds(tint: Int, alpha: Float, range: BackdropRange, sheen: Float, overTint: Boolean): List<Int> {
        val unlit = composite(tint, alpha, darkestGrey(range))
        val lit = if (overTint) {
            composite(WHITE, sheen, composite(tint, alpha, lightestGrey(range)))
        } else {
            composite(tint, alpha, composite(WHITE, sheen, lightestGrey(range)))
        }
        return listOf(unlit, lit)
    }

    /**
     * The most white sheen, in 1/255 steps, that the translucency's light may add without breaking [content]'s contrast
     * ([sheenBackgrounds]); 0 when there is no headroom at all.
     */
    fun maximumSheen(
        tint: Int,
        alpha: Float,
        content: Int,
        range: BackdropRange,
        overTint: Boolean,
        minRatio: Double = ColorMath.AA_CONTRAST,
    ): Float {
        for (step in 255 downTo 0) {
            val sheen = step / 255f
            if (isReadable(content, sheenBackgrounds(tint, alpha, range, sheen, overTint), minRatio)) return sheen
        }
        return 0f
    }

    /** The range's bounds on the 8-bit grid, rounded outwards so the model never shows less than the surface can. */
    private fun darkestGrey(range: BackdropRange): Int = grey(floor(range.darkest.coerceIn(0f, 1f) * 255f + GRID_SLACK).toInt())

    private fun lightestGrey(range: BackdropRange): Int = grey(ceil(range.lightest.coerceIn(0f, 1f) * 255f - GRID_SLACK).toInt())

    private fun grey(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Float noise on a bound that sits on the grid (0.4f * 255 is not exactly 102). */
    private const val GRID_SLACK = 1e-3f

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
}

/**
 * What a translucent surface paints for a theme and [TranslucentTier]: the [tint] at [tintAlpha] over a backdrop squeezed into
 * [range] by the surface's colour filter (flat translucency sees the full range), and the [label] colour readable on it.
 * Every value keeps the label at WCAG AA over pure white and pure black content.
 */
data class TranslucentMaterial(val tint: Int, val tintAlpha: Float, val range: TranslucentContrast.BackdropRange, val label: Int) {

    /** [color] adjusted to stay readable on this material (for accent-coloured text and icons). */
    fun contentColor(color: Int): Int = TranslucentContrast.readableOn(color, tint, tintAlpha, range)

    companion object {
        private const val DARK_TINT = 0xFF1C1C1E.toInt()
        private const val LIGHT_TINT = 0xFFFFFFFF.toInt()
        private const val DARK_LABEL = 0xFFFFFFFF.toInt()
        private const val LIGHT_LABEL = 0xFF000000.toInt()

        /** Today's flat translucency. */
        private const val FLAT_ALPHA = 0.72f

        /** Flat translucency for high-contrast text. */
        private const val OPAQUE_ALPHA = 0.92f

        /**
         * Sampling translucency: the design's tint, raised when contrast needs more. The liquid tier keeps it thin, so
         * covers and text under it keep their own colour (the label's contrast comes from the range below, not a
         * heavier tint); frosted translucency, which cannot glow, keeps more frost.
         */
        private const val LIQUID_DARK_ALPHA = 0.12f
        private const val LIQUID_LIGHT_ALPHA = 0.14f
        private const val FROSTED_DARK_ALPHA = 0.24f
        private const val FROSTED_LIGHT_ALPHA = 0.24f

        /**
         * The grey (0..1) dark translucency shows over black content: about #1F1F1F before the tint, lifted from black but
         * thin enough that a black list still reads dark through the pill (U9 allows no less than ~0.12).
         */
        private const val LIQUID_DARK_FLOOR = 0.12f

        /** Clear translucency: at most this much white sheen. */
        private const val SHEEN_ALPHA = 0.15f

        /**
         * Sampling translucency dims (dark) or lightens (light) what it shows, as iOS translucency does, just as far as
         * its label needs: liquid translucency lets more of the content's brightness through than frosted. Dark liquid
         * translucency also lifts black a little, as iOS dark translucency does: over the black list a round button's
         * centre must not read as a hole under its bright rim, and at 0.18 the library pills read as a grey slab, so
         * the lift is thin (about #1F). White labels lose nothing (their contrast is bound by
         * [TranslucentContrast.BackdropRange.lightest]).
         */
        private val LiquidDarkRange = TranslucentContrast.BackdropRange(LIQUID_DARK_FLOOR, 0.45f, eased = true)
        private val LiquidLightRange = TranslucentContrast.BackdropRange(0.52f, 1f, eased = true)
        private val FrostedDarkRange = TranslucentContrast.BackdropRange(0f, 0.40f)
        private val FrostedLightRange = TranslucentContrast.BackdropRange(0.60f, 1f)

        /**
         * The material for a [dark] or light theme. [tinted] false makes clear translucency (e.g. over an already dark
         * backdrop): a light white sheen within what the label's contrast allows (at most [SHEEN_ALPHA], at least what
         * a dark label needs), or, when no sheen keeps the label readable, the thinnest theme tint that does.
         */
        fun of(dark: Boolean, tier: TranslucentTier, highContrast: Boolean, tinted: Boolean = true): TranslucentMaterial {
            val tint = if (dark) DARK_TINT else LIGHT_TINT
            val label = if (dark) DARK_LABEL else LIGHT_LABEL
            val (designAlpha, range) = when {
                highContrast -> OPAQUE_ALPHA to TranslucentContrast.BackdropRange.Full
                tier == TranslucentTier.FLAT -> FLAT_ALPHA to TranslucentContrast.BackdropRange.Full
                tier == TranslucentTier.LIQUID -> if (dark) LIQUID_DARK_ALPHA to LiquidDarkRange else LIQUID_LIGHT_ALPHA to LiquidLightRange
                else -> if (dark) FROSTED_DARK_ALPHA to FrostedDarkRange else FROSTED_LIGHT_ALPHA to FrostedLightRange
            }
            if (!tinted && !highContrast) {
                TranslucentContrast.maximumAlpha(LIGHT_TINT, label, range)?.let { most ->
                    val sheen = maxOf(minOf(most, SHEEN_ALPHA), TranslucentContrast.minimumAlpha(LIGHT_TINT, label, range))
                    return TranslucentMaterial(LIGHT_TINT, sheen, range, label)
                }
            }
            val alpha = maxOf(if (tinted) designAlpha else 0f, TranslucentContrast.minimumAlpha(tint, label, range))
            return TranslucentMaterial(tint, alpha, range, label)
        }
    }
}
