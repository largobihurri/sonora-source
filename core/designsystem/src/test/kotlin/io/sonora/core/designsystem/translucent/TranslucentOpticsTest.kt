package io.sonora.core.designsystem.translucent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TranslucentOpticsTest {

    private fun optics(tier: TranslucentTier, dark: Boolean = true, tinted: Boolean = true, highContrast: Boolean = false) =
        TranslucentOptics.of(tier, TranslucentMaterial.of(dark, tier, highContrast, tinted), highContrast)

    @Test
    fun `the liquid tier glows with the content's own colours, bent and dispersed at the edge`() {
        val liquid = optics(TranslucentTier.LIQUID)

        assertThat(liquid.saturation).isGreaterThan(1f)
        assertThat(liquid.bloom).isGreaterThan(0f)
        assertThat(liquid.edgeGlow).isGreaterThan(0f)
        assertThat(liquid.dispersion).isTrue()
        assertThat(liquid.rimHue).isGreaterThan(0f)
        assertThat(liquid.refractionAmountDp).isGreaterThan(30f)
        assertThat(liquid.reflectionAlpha).isGreaterThan(0f)
        // The lens magnifies the label's reflection a little.
        assertThat(liquid.reflectionScale).isGreaterThan(1f)
        // A light blur keeps rows and thumbnails legible as shapes for the lens to bend, and a thin top sheen leaves
        // the dark translucency clear instead of a grey veil.
        assertThat(liquid.blurDp).isAtMost(2f)
        assertThat(liquid.sheenAlpha).isAtMost(0.06f)
    }

    @Test
    fun `frosted translucency degrades to colour filters and white light`() {
        val frosted = optics(TranslucentTier.FROSTED)

        assertThat(frosted.saturation).isGreaterThan(1f)
        assertThat(frosted.bloom).isEqualTo(0f)
        assertThat(frosted.edgeGlow).isEqualTo(0f)
        assertThat(frosted.dispersion).isFalse()
        assertThat(frosted.refractionAmountDp).isEqualTo(0f)
        assertThat(frosted.rimHue).isEqualTo(0f)
        assertThat(frosted.reflectionAlpha).isGreaterThan(0f)
        assertThat(frosted.reflectionScale).isEqualTo(1f)
        assertThat(frosted.blurDp).isGreaterThan(optics(TranslucentTier.LIQUID).blurDp)
        // The frosted gloss keeps its own sheen; it does not inherit Liquid's thinner one.
        assertThat(frosted.sheenAlpha).isWithin(1e-6f).of(0.1f)
        assertThat(frosted.sheenAlpha).isGreaterThan(optics(TranslucentTier.LIQUID).sheenAlpha)
    }

    @Test
    fun `flat translucency samples nothing, so it neither glows nor reflects`() {
        val flat = optics(TranslucentTier.FLAT)

        assertThat(flat.saturation).isEqualTo(1f)
        assertThat(flat.bloom).isEqualTo(0f)
        assertThat(flat.edgeGlow).isEqualTo(0f)
        assertThat(flat.blurDp).isEqualTo(0f)
        assertThat(flat.reflectionAlpha).isEqualTo(0f)
        assertThat(flat.rimAlpha).isGreaterThan(0f)
        assertThat(flat.bandAlpha).isGreaterThan(0f)
    }

    @Test
    fun `high contrast keeps only a plain rim`() {
        val strict = optics(TranslucentTier.FLAT, highContrast = true)

        assertThat(strict.rimAlpha).isGreaterThan(0f)
        assertThat(strict.sheenAlpha).isEqualTo(0f)
        assertThat(strict.bandAlpha).isEqualTo(0f)
        assertThat(strict.causticAlpha).isEqualTo(0f)
        assertThat(strict.reflectionAlpha).isEqualTo(0f)
    }

    @Test
    fun `every tier lights a brighter rim than its inner band`() {
        for (tier in TranslucentTier.entries) {
            val o = optics(tier)
            assertThat(o.rimAlpha).isGreaterThan(o.bandAlpha)
            assertThat(o.causticAlpha).isGreaterThan(0f)
        }
    }

    @Test
    fun `the rim, band, caustic and edge glow stay in the edge zone`() {
        for (tier in TranslucentTier.entries) {
            for (dark in listOf(true, false)) {
                val o = optics(tier, dark)
                assertThat(o.rimWidthDp).isAtMost(TranslucentOptics.EDGE_ZONE_DP)
                assertThat(o.edgeLightAt(0f)).isGreaterThan(0f)
                assertThat(o.edgeLightAt(TranslucentOptics.EDGE_ZONE_DP)).isEqualTo(0f)
                assertThat(o.glowAt(TranslucentOptics.EDGE_ZONE_DP)).isEqualTo(0f)
                assertThat(o.glowAt(0f)).isEqualTo(o.edgeGlow)
            }
        }
    }

    @Test
    fun `whatever light reaches the label zone keeps every label at AA`() {
        for (tier in TranslucentTier.entries) {
            for (dark in listOf(true, false)) {
                for (tinted in listOf(true, false)) {
                    for (highContrast in listOf(false, true)) {
                        val material = TranslucentMaterial.of(dark, tier, highContrast, tinted)
                        val o = TranslucentOptics.of(tier, material, highContrast)
                        val zone = TranslucentOptics.EDGE_ZONE_DP
                        val sheen = generateSequence(zone) { it + 0.5f }.take(40).maxOf(o::edgeLightAt) + o.sheenAlpha
                        val backgrounds = TranslucentContrast.sheenBackgrounds(
                            material.tint, material.tintAlpha, material.range, sheen, overTint = tier == TranslucentTier.FLAT,
                        )
                        assertWithMessage("$tier dark=$dark tinted=$tinted").that(TranslucentContrast.isReadable(material.label, backgrounds)).isTrue()
                    }
                }
            }
        }
    }

    @Test
    fun `the soft sheen over the labels takes only the contrast headroom left`() {
        val darkClear = TranslucentMaterial.of(dark = true, TranslucentTier.LIQUID, highContrast = false, tinted = false)
        val headroom = TranslucentContrast.maximumSheen(darkClear.tint, darkClear.tintAlpha, darkClear.label, darkClear.range, overTint = false)

        assertThat(TranslucentOptics.of(TranslucentTier.LIQUID, darkClear).sheenAlpha).isAtMost(headroom)
        assertThat(optics(TranslucentTier.LIQUID, dark = false).sheenAlpha).isGreaterThan(0f)
    }

    @Test
    fun `the label's reflection falls away from the light`() {
        val o = optics(TranslucentTier.LIQUID)
        val rest = o.reflectionOffset(TranslucentPolicy.REST_LIGHT_ANGLE)

        assertThat(rest.x).isGreaterThan(0f)
        assertThat(rest.y).isWithin(1e-4f).of(rest.x)
        assertThat(rest.getDistance()).isWithin(1e-4f).of(o.reflectionDepthDp)
        val swung = o.reflectionOffset(TranslucentPolicy.REST_LIGHT_ANGLE + TranslucentPolicy.LIGHT_SWING)
        assertThat(swung.y).isGreaterThan(swung.x)
    }

    @Test
    fun `light labels add their reflection, dark labels darken theirs`() {
        assertThat(optics(TranslucentTier.LIQUID, dark = true).reflectionAdds).isTrue()
        assertThat(optics(TranslucentTier.LIQUID, dark = false).reflectionAdds).isFalse()
    }
}
