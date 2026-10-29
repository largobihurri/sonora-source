package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier
import org.junit.Test

/** What an overlay does to the app under it: blur and dim (phones) or dim alone (flat translucency, large windows, T13). */
class TranslucentOverlayStyleTest {

    private fun policy(tier: TranslucentTier) = TranslucentPolicy(tier, specularMotion = true, highContrast = false)

    @Test
    fun `a phone with liquid or frosted translucency blurs the app and dims it lightly`() {
        assertThat(overlayStyle(policy(TranslucentTier.LIQUID), large = false)).isEqualTo(OverlayStyle(blurs = true, dimAlpha = 0.22f))
        assertThat(overlayStyle(policy(TranslucentTier.FROSTED), large = false)).isEqualTo(OverlayStyle(blurs = true, dimAlpha = 0.22f))
    }

    @Test
    fun `flat translucency never blurs and dims the app more, on any window`() {
        assertThat(overlayStyle(policy(TranslucentTier.FLAT), large = false)).isEqualTo(OverlayStyle(blurs = false, dimAlpha = 0.5f))
        assertThat(overlayStyle(policy(TranslucentTier.FLAT), large = true)).isEqualTo(OverlayStyle(blurs = false, dimAlpha = 0.5f))
    }

    @Test
    fun `a large window never blurs the app, whatever the tier`() {
        for (tier in TranslucentTier.entries) assertThat(overlayStyle(policy(tier), large = true).blurs).isFalse()
    }

    @Test
    fun `a large window dims more than a blurring phone, to make up for the blur it lacks, and less than flat translucency`() {
        val dim = overlayStyle(policy(TranslucentTier.LIQUID), large = true).dimAlpha

        assertThat(dim).isGreaterThan(overlayStyle(policy(TranslucentTier.LIQUID), large = false).dimAlpha)
        assertThat(dim).isLessThan(overlayStyle(policy(TranslucentTier.FLAT), large = false).dimAlpha)
    }
}
