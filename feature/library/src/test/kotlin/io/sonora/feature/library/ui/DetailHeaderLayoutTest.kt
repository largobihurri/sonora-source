package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetailHeaderLayoutTest {

    @Test
    fun `a phone always keeps the centred header`() {
        assertThat(DetailHeaderLayout.of(isLarge = false, widthDp = 320f)).isEqualTo(DetailHeaderLayout.Centred)
        // A phone held sideways is wide, but it is not a large window.
        assertThat(DetailHeaderLayout.of(isLarge = false, widthDp = 704f)).isEqualTo(DetailHeaderLayout.Centred)
    }

    @Test
    fun `a large window with room puts the cover beside the titles`() {
        val layout = DetailHeaderLayout.of(isLarge = true, widthDp = 840f)
        assertThat(layout).isInstanceOf(DetailHeaderLayout.Beside::class.java)
    }

    @Test
    fun `a large window too narrow for two blocks keeps the cover above`() {
        // A tablet held upright with a sidebar leaves about 440 dp.
        assertThat(DetailHeaderLayout.of(isLarge = true, widthDp = 440f)).isEqualTo(DetailHeaderLayout.Centred)
        assertThat(DetailHeaderLayout.of(isLarge = true, widthDp = 559f)).isEqualTo(DetailHeaderLayout.Centred)
        assertThat(DetailHeaderLayout.of(isLarge = true, widthDp = 560f)).isInstanceOf(DetailHeaderLayout.Beside::class.java)
    }

    @Test
    fun `the cover grows with the room between a floor and a ceiling`() {
        fun cover(width: Float) = (DetailHeaderLayout.of(isLarge = true, widthDp = width) as DetailHeaderLayout.Beside).coverDp
        assertThat(cover(560f)).isEqualTo(DetailHeaderLayout.MIN_COVER_DP)
        assertThat(cover(840f)).isGreaterThan(cover(720f))
        assertThat(cover(2000f)).isEqualTo(DetailHeaderLayout.MAX_COVER_DP)
    }

    @Test
    fun `the Pad 6 gets a large cover in landscape and a smaller one upright`() {
        fun cover(width: Float) = (DetailHeaderLayout.of(isLarge = true, widthDp = width) as DetailHeaderLayout.Beside).coverDp
        // Landscape: the window capped at the 840 dp table width. Upright: 720 dp wide.
        assertThat(cover(840f)).isAtLeast(260f)
        assertThat(cover(720f)).isAtLeast(220f)
        assertThat(cover(840f)).isAtMost(DetailHeaderLayout.MAX_COVER_DP)
    }

    @Test
    fun `the titles always keep room beside the cover`() {
        for (width in 560..1400 step 20) {
            val layout = DetailHeaderLayout.of(isLarge = true, widthDp = width.toFloat()) as DetailHeaderLayout.Beside
            // Two 24 dp margins and a 24 dp gap around the cover; the titles keep at least 280 dp.
            assertThat(width - layout.coverDp - 72f).isAtLeast(280f)
        }
    }
}
