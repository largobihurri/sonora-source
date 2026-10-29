package io.sonora.core.designsystem.component

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier
import org.junit.Test

class TranslucentOverlayBlurTest {

    private fun policy(tier: TranslucentTier, limited: Boolean = false) =
        TranslucentPolicy(tier, specularMotion = true, highContrast = false, limited = limited)

    @Test
    fun `the app under an overlay is blurred behind liquid and frosted translucency`() {
        assertThat(overlayBlurs(policy(TranslucentTier.LIQUID))).isTrue()
        assertThat(overlayBlurs(policy(TranslucentTier.FROSTED))).isTrue()
    }

    @Test
    fun `flat translucency only dims the app under an overlay`() {
        // The switch off, Android 11 and older, high-contrast text, or a GPU that could not keep up (limited).
        assertThat(overlayBlurs(policy(TranslucentTier.FLAT))).isFalse()
        assertThat(overlayBlurs(policy(TranslucentTier.FLAT, limited = true))).isFalse()
    }

    @Test
    fun `an overlay panel over flat translucency has an opaque backing so the app's text does not read through`() {
        val dark = overlayPanelBacking(policy(TranslucentTier.FLAT), dark = true)
        val light = overlayPanelBacking(policy(TranslucentTier.FLAT), dark = false)

        assertThat(dark).isEqualTo(Color(0xFF1C1C1E))
        assertThat(light).isEqualTo(Color.White)
    }

    @Test
    fun `an overlay panel over the blur needs no backing`() {
        assertThat(overlayPanelBacking(policy(TranslucentTier.LIQUID), dark = true)).isNull()
        assertThat(overlayPanelBacking(policy(TranslucentTier.FROSTED), dark = false)).isNull()
    }

    @Test
    fun `the dim is stronger when there is no blur`() {
        assertThat(overlayDimAlpha(blurs = false)).isGreaterThan(overlayDimAlpha(blurs = true))
        assertThat(overlayDimAlpha(blurs = true)).isEqualTo(0.22f)
        assertThat(overlayDimAlpha(blurs = false)).isEqualTo(0.5f)
    }
}
