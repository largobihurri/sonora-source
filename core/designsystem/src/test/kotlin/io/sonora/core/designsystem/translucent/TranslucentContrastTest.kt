package io.sonora.core.designsystem.translucent

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.theme.ColorMath
import org.junit.Test

class TranslucentContrastTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val darkTint = 0xFF1C1C1E.toInt()
    private val pink = 0xFFFF2D55.toInt()
    private val darkPink = 0xFFD30F45.toInt()

    /** Grey backdrops from black to white, the extremes included. */
    private val greys = (0..16).map { step -> (step * 255 / 16).let { 0xFF000000.toInt() or (it shl 16) or (it shl 8) or it } }

    private fun backdropGreys(range: TranslucentContrast.BackdropRange) = greys.map { grey ->
        val v = (range.map((grey and 0xFF) / 255f) * 255f).toInt()
        0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    }

    /** Every 1/255 level, black to white. */
    private val levels = (0..255).map { it / 255f }

    @Test
    fun `the dark liquid tier never darkens an already dark backdrop`() {
        val range = TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false, tinted = false).range

        // The album-green Now Playing backdrop behind the round buttons (about 0.25 per channel) stays at least that
        // bright: every level below the knee shows as it is or lighter, never darker.
        levels.filter { it <= TranslucentContrast.BackdropRange.KNEE * range.lightest }.forEach { assertThat(range.map(it)).isAtLeast(it - 1e-6f) }
        assertThat(range.map(0.25f)).isWithin(0.05f).of(0.25f)
        // Only the brightest content is eased down to the range's ceiling.
        assertThat(range.map(1f)).isWithin(1e-6f).of(range.lightest)
    }

    @Test
    fun `the dark liquid tier lifts a black backdrop just above black, as iOS dark translucency does`() {
        for (tinted in listOf(true, false)) {
            val material = TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false, tinted = tinted)

            // Over the black list the round buttons showed a near-black centre under a bright rim (U9), so black is
            // lifted. At 0.18 the library pills read as a grey slab, so the lift is thinner, but no lower than the
            // ~0.12-0.14 the owner allowed for U9 on 2026-10-05 (about #1F).
            assertThat(material.range.map(0f)).isAtLeast(0.12f)
            assertThat(material.range.map(0f)).isAtMost(0.14f)
            val centre = TranslucentContrast.composite(material.tint, material.tintAlpha, darkestGrey(material.range))
            // floor 0.12 -> grid 30; tinted composites to 30, clear (white at 6/255) to 35
            assertThat(centre and 0xFF).isAtLeast(28)
            // The ceiling, and with it the white label's contrast over bright content, stays where it was.
            assertThat(material.range.lightest).isWithin(1e-6f).of(0.45f)
        }
    }

    @Test
    fun `the lifted floor leaves the light and frosted tiers as they were`() {
        assertThat(TranslucentMaterial.of(dark = false, TranslucentTier.LIQUID, highContrast = false).range)
            .isEqualTo(TranslucentContrast.BackdropRange(0.52f, 1f, eased = true))
        assertThat(TranslucentMaterial.of(dark = true, TranslucentTier.FROSTED, highContrast = false).range)
            .isEqualTo(TranslucentContrast.BackdropRange(0f, 0.40f))
    }

    private fun darkestGrey(range: TranslucentContrast.BackdropRange): Int =
        (range.map(0f) * 255f).toInt().let { 0xFF000000.toInt() or (it shl 16) or (it shl 8) or it }

    @Test
    fun `the light liquid tier shows an already light backdrop as it is instead of lightening it`() {
        val range = TranslucentMaterial.of(dark = false, TranslucentTier.LIQUID, highContrast = false).range

        assertThat(range.map(0.9f)).isWithin(1e-6f).of(0.9f)
        assertThat(range.map(1f)).isEqualTo(1f)
        assertThat(range.map(0f)).isWithin(1e-6f).of(range.darkest)
    }

    @Test
    fun `every eased level stays inside the range, in order, and joins the untouched levels smoothly`() {
        val ranges = listOf(
            TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false).range,
            TranslucentMaterial.of(dark = false, TranslucentTier.LIQUID, highContrast = false).range,
            TranslucentContrast.BackdropRange(0.2f, 0.7f, eased = true),
        )
        for (range in ranges) {
            val mapped = levels.map(range::map)
            mapped.forEach { assertThat(it).isIn(Range.closed(range.darkest, range.lightest)) }
            mapped.zipWithNext().forEach { (a, b) -> assertThat(b).isAtLeast(a) }
            // No visible step: neighbouring levels never move apart by more than they were.
            mapped.zipWithNext().forEach { (a, b) -> assertThat(b - a).isAtMost(1f / 255f + 1e-5f) }
        }
    }

    @Test
    fun `a plain range scales every level, as a colour matrix does`() {
        val range = TranslucentMaterial.of(dark = true, TranslucentTier.FROSTED, highContrast = false).range

        assertThat(range.eased).isFalse()
        assertThat(range.map(0.5f)).isWithin(1e-6f).of(range.lightest * 0.5f)
        assertThat(TranslucentContrast.BackdropRange.Full.map(0.3f)).isWithin(1e-6f).of(0.3f)
    }

    @Test
    fun `compositing is plain source-over in sRGB`() {
        assertThat(TranslucentContrast.composite(white, 0f, black)).isEqualTo(black)
        assertThat(TranslucentContrast.composite(white, 1f, black)).isEqualTo(white)
        assertThat(TranslucentContrast.composite(white, 0.5f, black)).isEqualTo(0xFF808080.toInt())
    }

    @Test
    fun `the minimum tint keeps white text readable over a white backdrop`() {
        val alpha = TranslucentContrast.minimumAlpha(darkTint, white, TranslucentContrast.BackdropRange.Full)

        val worst = TranslucentContrast.worstBackgrounds(darkTint, alpha, TranslucentContrast.BackdropRange.Full)
        assertThat(worst.all { ColorMath.contrastRatio(white, it) >= ColorMath.AA_CONTRAST }).isTrue()
        // And it is the minimum: a visibly thinner tint fails.
        val thinner = TranslucentContrast.worstBackgrounds(darkTint, alpha - 0.02f, TranslucentContrast.BackdropRange.Full)
        assertThat(thinner.all { ColorMath.contrastRatio(white, it) >= ColorMath.AA_CONTRAST }).isFalse()
    }

    @Test
    fun `no tint is needed when the backdrop range is already dark enough`() {
        assertThat(TranslucentContrast.minimumAlpha(darkTint, white, TranslucentContrast.BackdropRange(0f, 0.3f))).isEqualTo(0f)
    }

    @Test
    fun `text that no tint can rescue asks for an opaque surface`() {
        // Mid grey on a mid-grey tint never reaches 4.5:1.
        assertThat(TranslucentContrast.minimumAlpha(0xFF777777.toInt(), 0xFF888888.toInt(), TranslucentContrast.BackdropRange.Full)).isEqualTo(1f)
    }

    @Test
    fun `content is unreadable when some backdrop between the extremes matches it`() {
        // A grey that passes 4.5:1 against both pure black and pure white still meets its own luminance in between.
        val betweenGrey = 0xFF767676.toInt()
        assertThat(TranslucentContrast.isReadable(betweenGrey, listOf(black, white))).isFalse()
        assertThat(TranslucentContrast.isReadable(white, listOf(black, 0xFF303030.toInt()))).isTrue()
    }

    @Test
    fun `an accent is adjusted, keeping its hue, until it reads over every backdrop translucency can show`() {
        for (dark in listOf(true, false)) {
            for (tier in TranslucentTier.entries) {
                for (highContrast in listOf(false, true)) {
                    val material = TranslucentMaterial.of(dark, tier, highContrast)
                    val accent = material.contentColor(if (dark) pink else darkPink)
                    backdropGreys(material.range).forEach { backdrop ->
                        val behind = TranslucentContrast.composite(material.tint, material.tintAlpha, backdrop)
                        assertThat(ColorMath.contrastRatio(accent, behind)).isAtLeast(ColorMath.AA_CONTRAST)
                    }
                    val hueShift = Math.abs(ColorMath.toOklch(accent).h - ColorMath.toOklch(if (dark) pink else darkPink).h)
                    if (accent != white && accent != black) assertThat(hueShift).isLessThan(8.0)
                }
            }
        }
    }

    @Test
    fun `every material keeps its label at AA over very bright and very dark content`() {
        for (dark in listOf(true, false)) {
            for (tier in TranslucentTier.entries) {
                for (highContrast in listOf(false, true)) {
                    val material = TranslucentMaterial.of(dark, tier, highContrast)
                    backdropGreys(material.range).forEach { backdrop ->
                        val behind = TranslucentContrast.composite(material.tint, material.tintAlpha, backdrop)
                        assertThat(ColorMath.contrastRatio(material.label, behind)).isAtLeast(ColorMath.AA_CONTRAST)
                    }
                }
            }
        }
    }

    @Test
    fun `labels are white on dark translucency and black on light translucency`() {
        assertThat(TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false).label).isEqualTo(white)
        assertThat(TranslucentMaterial.of(dark = false, TranslucentTier.LIQUID, highContrast = false).label).isEqualTo(black)
    }

    @Test
    fun `the flat tier keeps today's translucency and high contrast makes it more opaque`() {
        val flat = TranslucentMaterial.of(dark = true, TranslucentTier.FLAT, highContrast = false)
        val opaque = TranslucentMaterial.of(dark = true, TranslucentTier.FLAT, highContrast = true)

        assertThat(flat.tintAlpha).isWithin(1e-6f).of(0.72f)
        assertThat(flat.range).isEqualTo(TranslucentContrast.BackdropRange.Full)
        assertThat(opaque.tintAlpha).isGreaterThan(flat.tintAlpha)
        assertThat(TranslucentMaterial.of(dark = false, TranslucentTier.FLAT, highContrast = false).tintAlpha).isWithin(1e-6f).of(0.72f)
    }

    @Test
    fun `sampling translucency compresses the backdrop toward the theme so the tint can stay thin`() {
        val darkTranslucent = TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false)
        val lightTranslucent = TranslucentMaterial.of(dark = false, TranslucentTier.FROSTED, highContrast = false)

        assertThat(darkTranslucent.range.lightest).isLessThan(0.5f)
        assertThat(lightTranslucent.range.darkest).isGreaterThan(0.5f)
        assertThat(darkTranslucent.tintAlpha).isLessThan(0.5f)
        assertThat(lightTranslucent.tintAlpha).isLessThan(0.5f)
    }

    @Test
    fun `clear translucency trades the tint for a light sheen as far as the label's contrast allows`() {
        val clear = TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false, tinted = false)
        assertThat(clear.tint).isEqualTo(white)
        assertThat(clear.tintAlpha).isGreaterThan(0f)
        assertThat(clear.tintAlpha).isAtMost(0.15f)
        backdropGreys(clear.range).forEach { backdrop ->
            val behind = TranslucentContrast.composite(clear.tint, clear.tintAlpha, backdrop)
            assertThat(ColorMath.contrastRatio(clear.label, behind)).isAtLeast(ColorMath.AA_CONTRAST)
        }
    }

    @Test
    fun `clear translucency keeps its label at AA in every tier and theme`() {
        for (dark in listOf(true, false)) {
            for (tier in TranslucentTier.entries) {
                val clear = TranslucentMaterial.of(dark, tier, highContrast = false, tinted = false)
                backdropGreys(clear.range).forEach { backdrop ->
                    val behind = TranslucentContrast.composite(clear.tint, clear.tintAlpha, backdrop)
                    assertThat(ColorMath.contrastRatio(clear.label, behind)).isAtLeast(ColorMath.AA_CONTRAST)
                }
            }
        }
    }

    @Test
    fun `clear translucency that a sheen cannot keep readable falls back to the thinnest dark tint`() {
        val flat = TranslucentMaterial.of(dark = true, TranslucentTier.FLAT, highContrast = false, tinted = false)

        assertThat(flat.tint).isEqualTo(darkTint)
        assertThat(flat.tintAlpha).isEqualTo(TranslucentContrast.minimumAlpha(darkTint, white, TranslucentContrast.BackdropRange.Full))
    }

    @Test
    fun `the liquid tier is clearer than the frosted tier, which keeps more frost`() {
        val liquidDark = TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false)
        val frostedDark = TranslucentMaterial.of(dark = true, TranslucentTier.FROSTED, highContrast = false)
        val liquidLight = TranslucentMaterial.of(dark = false, TranslucentTier.LIQUID, highContrast = false)
        val frostedLight = TranslucentMaterial.of(dark = false, TranslucentTier.FROSTED, highContrast = false)

        assertThat(liquidDark.tintAlpha).isAtMost(0.15f)
        assertThat(liquidLight.tintAlpha).isAtMost(0.15f)
        assertThat(liquidDark.tintAlpha).isLessThan(frostedDark.tintAlpha)
        assertThat(liquidLight.tintAlpha).isLessThan(frostedLight.tintAlpha)
        // More of the content's own brightness shows through.
        assertThat(liquidDark.range.lightest).isGreaterThan(frostedDark.range.lightest)
        assertThat(liquidLight.range.darkest).isLessThan(frostedLight.range.darkest)
    }

    @Test
    fun `sampling translucency lights what it shows under its tint, flat translucency over it`() {
        val halfBlack = 0.5f
        val under = TranslucentContrast.sheenBackgrounds(black, halfBlack, TranslucentContrast.BackdropRange.Full, 0.5f, overTint = false)
        val over = TranslucentContrast.sheenBackgrounds(black, halfBlack, TranslucentContrast.BackdropRange.Full, 0.5f, overTint = true)

        // The darkest backdrop is never lit: black under half black.
        assertThat(under.first()).isEqualTo(black)
        assertThat(over.first()).isEqualTo(black)
        // White lit by more white stays white, then half black dims it.
        assertThat(under.last()).isEqualTo(0xFF808080.toInt())
        // Half white over the dimmed white.
        assertThat(over.last()).isEqualTo(0xFFC0C0C0.toInt())
    }

    @Test
    fun `the light a dark translucent surface adds is capped where it would wash out white text`() {
        val material = TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false)
        val sheen = TranslucentContrast.maximumSheen(material.tint, material.tintAlpha, material.label, material.range, overTint = false)

        assertThat(sheen).isGreaterThan(0f)
        assertThat(sheen).isLessThan(1f)
        val at = TranslucentContrast.sheenBackgrounds(material.tint, material.tintAlpha, material.range, sheen, overTint = false)
        val past = TranslucentContrast.sheenBackgrounds(material.tint, material.tintAlpha, material.range, sheen + 2 / 255f, overTint = false)
        assertThat(TranslucentContrast.isReadable(material.label, at)).isTrue()
        assertThat(TranslucentContrast.isReadable(material.label, past)).isFalse()
    }

    @Test
    fun `black text on light translucency takes any light`() {
        val material = TranslucentMaterial.of(dark = false, TranslucentTier.LIQUID, highContrast = false)

        assertThat(TranslucentContrast.maximumSheen(material.tint, material.tintAlpha, material.label, material.range, overTint = false))
            .isEqualTo(1f)
    }

    @Test
    fun `the thickest readable tint is found, or none at all`() {
        val range = TranslucentContrast.BackdropRange(0f, 0.4f)
        val alpha = TranslucentContrast.maximumAlpha(white, white, range)!!

        assertThat(TranslucentContrast.isReadable(white, TranslucentContrast.worstBackgrounds(white, alpha, range))).isTrue()
        assertThat(TranslucentContrast.isReadable(white, TranslucentContrast.worstBackgrounds(white, alpha + 0.02f, range))).isFalse()
        assertThat(TranslucentContrast.maximumAlpha(white, white, TranslucentContrast.BackdropRange.Full)).isNull()
    }
}
