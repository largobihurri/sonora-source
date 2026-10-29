package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentOverlayLightTest {

    @Test
    fun `an open overlay holds its light at rest`() {
        assertThat(overlayLightShift(1f)).isEqualTo(0f)
    }

    @Test
    fun `the light starts fully swung when the translucent surface becomes visible`() {
        assertThat(overlayLightShift(OVERLAY_FADE_END)).isEqualTo(1f)
        assertThat(overlayLightShift(0f)).isEqualTo(1f)
    }

    @Test
    fun `the light sweeps back toward rest as the overlay opens`() {
        val early = overlayLightShift(0.7f)
        val late = overlayLightShift(0.9f)

        assertThat(early).isGreaterThan(late)
        assertThat(late).isGreaterThan(0f)
    }

    @Test
    fun `the spring overshoot swings the light slightly past rest and back`() {
        val past = overlayLightShift(1.05f)

        assertThat(past).isLessThan(0f)
        assertThat(past).isGreaterThan(-0.2f)
    }

    @Test
    fun `the shift never leaves the light's range`() {
        for (step in -10..30) {
            val shift = overlayLightShift(step / 10f)
            assertThat(shift).isAtLeast(-1f)
            assertThat(shift).isAtMost(1f)
        }
    }
}
