package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.layout.WindowClass
import org.junit.Test

/** The artwork backdrop is smooth, so a large screen draws it small and scales it up (T10). */
class BackdropResolutionTest {

    @Test
    fun `a phone draws its backdrop at full size, as before`() {
        assertThat(backdropDivisor(WindowClass.of(411, 891))).isEqualTo(1)
        assertThat(backdropDivisor(WindowClass.of(891, 411))).isEqualTo(1)
    }

    @Test
    fun `a tablet draws its backdrop a quarter of its size per side, in either orientation`() {
        assertThat(backdropDivisor(WindowClass.of(720, 1152))).isEqualTo(4)
        assertThat(backdropDivisor(WindowClass.of(1152, 720))).isEqualTo(4)
    }

    @Test
    fun `the Pad 6 backdrop is 450 x 720 px instead of 1800 x 2880`() {
        assertThat(reducedBackdropSize(IntSize(1800, 2880), 4)).isEqualTo(IntSize(450, 720))
        assertThat(reducedBackdropSize(IntSize(2880, 1800), 4)).isEqualTo(IntSize(720, 450))
    }

    @Test
    fun `a size that does not divide rounds up, so the scale never goes below one`() {
        val reduced = reducedBackdropSize(IntSize(1801, 2881), 4)
        assertThat(reduced).isEqualTo(IntSize(451, 721))
        assertThat(1801f / reduced.width).isAtMost(4f)
        assertThat(1801f / reduced.width).isAtLeast(1f)
    }

    @Test
    fun `a divisor of one, or a window not laid out yet, keeps its size and never reaches zero`() {
        assertThat(reducedBackdropSize(IntSize(1080, 2400), 1)).isEqualTo(IntSize(1080, 2400))
        assertThat(reducedBackdropSize(IntSize(0, 0), 4)).isEqualTo(IntSize(1, 1))
    }

    @Test
    fun `the blur radius and corner shrink with the picture, so it looks the same once scaled up`() {
        assertThat(reducedBackdropDp(80.dp, 4)).isEqualTo(20.dp)
        assertThat(reducedBackdropDp(80.dp, 1)).isEqualTo(80.dp)
    }
}
