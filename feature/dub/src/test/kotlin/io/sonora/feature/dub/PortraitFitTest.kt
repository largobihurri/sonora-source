package io.sonora.feature.dub

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The upright phone layout: the flexible element takes the room left, never less than its minimum. */
class PortraitFitTest {

    @Test
    fun `with room to spare the square stops at its largest and nothing scrolls`() {
        val fit = PortraitFit.of(viewport = 1000, above = 300, below = 400, gaps = 16, minFlex = 100, maxFlex = 250)

        assertThat(fit.band).isEqualTo(284)
        assertThat(fit.flex).isEqualTo(250)
        assertThat(fit.height).isEqualTo(1000)
    }

    @Test
    fun `a short screen keeps the minimum and scrolls by what is missing`() {
        val fit = PortraitFit.of(viewport = 700, above = 300, below = 400, gaps = 16, minFlex = 100, maxFlex = 250)

        assertThat(fit.band).isEqualTo(100)
        assertThat(fit.flex).isEqualTo(100)
        assertThat(fit.height).isEqualTo(816)
    }

    @Test
    fun `between the minimum and the largest the square fills the room exactly`() {
        val fit = PortraitFit.of(viewport = 900, above = 300, below = 400, gaps = 16, minFlex = 100, maxFlex = 250)

        assertThat(fit.flex).isEqualTo(184)
        assertThat(fit.height).isEqualTo(900)
    }

    @Test
    fun `a narrow width caps the square below its minimum`() {
        val fit = PortraitFit.of(viewport = 700, above = 300, below = 400, gaps = 16, minFlex = 100, maxFlex = 80)

        assertThat(fit.flex).isEqualTo(80)
        assertThat(fit.band).isEqualTo(80)
        assertThat(fit.height).isEqualTo(796)
    }

    @Test
    fun `an element without a largest size takes the whole room`() {
        val fit = PortraitFit.of(viewport = 1000, above = 300, below = 0, gaps = 8, minFlex = 240, maxFlex = Int.MAX_VALUE)

        assertThat(fit.flex).isEqualTo(692)
        assertThat(fit.height).isEqualTo(1000)
    }
}
