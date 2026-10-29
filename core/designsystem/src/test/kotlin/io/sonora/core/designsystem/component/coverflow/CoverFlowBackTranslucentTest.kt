package io.sonora.core.designsystem.component.coverflow

import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.translucent.TranslucentContrast
import io.sonora.core.designsystem.translucent.TranslucentMaterial
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.theme.ArtworkPalette
import io.sonora.core.designsystem.theme.ColorMath
import org.junit.Test

class CoverFlowBackTranslucentTest {

    private val white = 0xFFFFFFFF.toInt()
    private val dominants = listOf(white, 0xFFD0302A.toInt(), 0xFF0B1A3A.toInt(), 0xFFF2E14C.toInt(), 0xFF101010.toInt(), null)
    private val translucentes = listOf(TranslucentTier.LIQUID, TranslucentTier.FROSTED).map { TranslucentMaterial.of(dark = true, tier = it, highContrast = false, tinted = false) }

    private fun albumColors() = dominants.map { ArtworkPalette.from(it).background }

    private fun secondaryOn(background: Int) = TranslucentContrast.composite(white, CoverFlowBackTranslucent.SECONDARY_TEXT_ALPHA, background)

    @Test
    fun `the tint is the album colour itself`() {
        for (translucent in translucentes) {
            for (album in albumColors()) {
                assertThat(CoverFlowBackTranslucent.tint(album, translucent).color).isEqualTo(album)
            }
        }
    }

    @Test
    fun `labels stay readable at the rim over any backdrop the translucency shows`() {
        for (translucent in translucentes) {
            for (album in albumColors()) {
                val tint = CoverFlowBackTranslucent.tint(album, translucent)

                val shown = CoverFlowBackTranslucent.shown(translucent, tint.color, tint.rimAlpha)
                assertThat(shown).hasSize(2)
                assertThat(TranslucentContrast.isReadable(white, shown)).isTrue()
                assertThat(tint.rimAlpha).isAtLeast(CoverFlowBackTranslucent.RIM_ALPHA)
            }
        }
    }

    @Test
    fun `behind the list the tint is stronger and keeps titles at AAA and secondary text at AA`() {
        for (translucent in translucentes) {
            for (album in albumColors()) {
                val tint = CoverFlowBackTranslucent.tint(album, translucent)

                assertThat(tint.bodyAlpha).isAtLeast(maxOf(tint.rimAlpha, CoverFlowBackTranslucent.BODY_ALPHA))
                assertThat(tint.bodyAlpha).isAtMost(1f)
                for (background in CoverFlowBackTranslucent.shown(translucent, tint.color, tint.bodyAlpha)) {
                    assertThat(ColorMath.contrastRatio(white, background)).isAtLeast(CoverFlowBackTranslucent.BODY_CONTRAST)
                    assertThat(ColorMath.contrastRatio(secondaryOn(background), background)).isAtLeast(ColorMath.AA_CONTRAST)
                }
            }
        }
    }

    @Test
    fun `the body alpha is the design alpha unless contrast needs more, and then no more than it needs`() {
        for (translucent in translucentes) {
            for (album in albumColors()) {
                val tint = CoverFlowBackTranslucent.tint(album, translucent)
                if (tint.bodyAlpha <= CoverFlowBackTranslucent.BODY_ALPHA) continue

                val thinner = CoverFlowBackTranslucent.shown(translucent, tint.color, tint.bodyAlpha - 1f / 255f)
                val readable = thinner.all {
                    ColorMath.contrastRatio(white, it) >= CoverFlowBackTranslucent.BODY_CONTRAST &&
                        ColorMath.contrastRatio(secondaryOn(it), it) >= ColorMath.AA_CONTRAST
                }
                assertThat(readable).isFalse()
            }
        }
    }

    @Test
    fun `a white cover needs more than the design tint behind the list`() {
        val translucent = TranslucentMaterial.of(dark = true, tier = TranslucentTier.LIQUID, highContrast = false, tinted = false)
        val tint = CoverFlowBackTranslucent.tint(ArtworkPalette.from(white).background, translucent)

        assertThat(tint.bodyAlpha).isGreaterThan(CoverFlowBackTranslucent.BODY_ALPHA)
    }

    @Test
    fun `the body overlay over the rim tint adds up to the body tint`() {
        val tint = CoverFlowBackTranslucent.Tint(white, rimAlpha = 0.4f, bodyAlpha = 0.7f)

        val total = 1f - (1f - tint.rimAlpha) * (1f - tint.bodyOverlayAlpha)
        assertThat(total).isWithin(1e-5f).of(0.7f)
        assertThat(CoverFlowBackTranslucent.Tint(white, 0.5f, 0.5f).bodyOverlayAlpha).isEqualTo(0f)
        assertThat(CoverFlowBackTranslucent.Tint(white, 0.5f, 1f).bodyOverlayAlpha).isEqualTo(1f)
    }

    @Test
    fun `sampling translucency with a backdrop shows the translucent back`() {
        for (sdk in listOf(31, 33, 36)) {
            val policy = TranslucentPolicy.resolve(sdk, enabled = true, reducedMotion = false, highContrastText = false)
            assertThat(CoverFlowBackTranslucent.showsTranslucent(policy, hasBackdrop = true)).isTrue()
        }
        val still = TranslucentPolicy.resolve(36, enabled = true, reducedMotion = true, highContrastText = false)
        assertThat(CoverFlowBackTranslucent.showsTranslucent(still, hasBackdrop = true)).isTrue()
    }

    @Test
    fun `Translucency off, high contrast, the flat tier or no backdrop fall back to the opaque back`() {
        val off = TranslucentPolicy.resolve(36, enabled = false, reducedMotion = false, highContrastText = false)
        val highContrast = TranslucentPolicy.resolve(36, enabled = true, reducedMotion = false, highContrastText = true)
        val oldAndroid = TranslucentPolicy.resolve(30, enabled = true, reducedMotion = false, highContrastText = false)
        val liquid = TranslucentPolicy.resolve(36, enabled = true, reducedMotion = false, highContrastText = false)

        assertThat(CoverFlowBackTranslucent.showsTranslucent(off, hasBackdrop = true)).isFalse()
        assertThat(CoverFlowBackTranslucent.showsTranslucent(highContrast, hasBackdrop = true)).isFalse()
        assertThat(CoverFlowBackTranslucent.showsTranslucent(liquid.copy(highContrast = true), hasBackdrop = true)).isFalse()
        assertThat(CoverFlowBackTranslucent.showsTranslucent(oldAndroid, hasBackdrop = true)).isFalse()
        assertThat(CoverFlowBackTranslucent.showsTranslucent(TranslucentPolicy.Flat, hasBackdrop = true)).isFalse()
        assertThat(CoverFlowBackTranslucent.showsTranslucent(liquid, hasBackdrop = false)).isFalse()
    }

    @Test
    fun `the opaque veil covers the translucent back while the card turns and lifts once it has settled`() {
        // Opening: still turning, nothing of the translucent back shows.
        assertThat(CoverFlowBackTranslucent.veil(settled = 0f, flip = 0.6f)).isEqualTo(1f)
        assertThat(CoverFlowBackTranslucent.veil(settled = 0f, flip = 1f)).isEqualTo(1f)
        // Settled flat: the translucent back fades in.
        assertThat(CoverFlowBackTranslucent.veil(settled = 0.5f, flip = 1f)).isWithin(1e-5f).of(0.5f)
        assertThat(CoverFlowBackTranslucent.veil(settled = 1f, flip = 1f)).isEqualTo(0f)
    }

    @Test
    fun `closing, the veil is back before the card has turned far`() {
        val halfway = 1f - CoverFlowBackTranslucent.CLOSE_FADE / 2f
        assertThat(CoverFlowBackTranslucent.veil(settled = 1f, flip = halfway)).isWithin(1e-5f).of(0.5f)
        assertThat(CoverFlowBackTranslucent.veil(settled = 1f, flip = 1f - CoverFlowBackTranslucent.CLOSE_FADE)).isWithin(1e-5f).of(1f)
        assertThat(CoverFlowBackTranslucent.veil(settled = 1f, flip = 0.5f)).isEqualTo(1f)
    }
}
