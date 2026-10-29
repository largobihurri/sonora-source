package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When Now Playing's sheet has rounded corners (read in the layer, never in composition: T10). */
class SheetCornersTest {

    @Test
    fun `a sheet resting open is square`() {
        assertThat(sheetCornered(progress = 1f, backProgress = 0f, bubbled = false)).isFalse()
    }

    @Test
    fun `a sheet opening, closing or dragged is rounded`() {
        assertThat(sheetCornered(progress = 0.4f, backProgress = 0f, bubbled = false)).isTrue()
        assertThat(sheetCornered(progress = 0.999f, backProgress = 0f, bubbled = false)).isTrue()
    }

    @Test
    fun `a sheet shrinking with the back gesture is rounded though it is still fully open`() {
        assertThat(sheetCornered(progress = 1f, backProgress = 0.2f, bubbled = false)).isTrue()
    }

    @Test
    fun `a bubble draws its own corners`() {
        assertThat(sheetCornered(progress = 0.4f, backProgress = 0f, bubbled = true)).isFalse()
        assertThat(sheetCornered(progress = 1f, backProgress = 0.2f, bubbled = true)).isFalse()
    }
}
