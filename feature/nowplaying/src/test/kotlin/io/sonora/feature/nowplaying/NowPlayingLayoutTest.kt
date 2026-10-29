package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NowPlayingLayoutTest {

    @Test
    fun `a window taller than it is wide is portrait`() {
        assertThat(nowPlayingLayout(width = 411.dp, height = 891.dp)).isEqualTo(NowPlayingLayout.Portrait)
    }

    @Test
    fun `a window wider than it is tall is landscape`() {
        assertThat(nowPlayingLayout(width = 891.dp, height = 411.dp)).isEqualTo(NowPlayingLayout.Landscape)
    }

    @Test
    fun `a wide tablet window is landscape too`() {
        assertThat(nowPlayingLayout(width = 1280.dp, height = 800.dp)).isEqualTo(NowPlayingLayout.Landscape)
    }

    @Test
    fun `a square window keeps the portrait layout`() {
        assertThat(nowPlayingLayout(width = 600.dp, height = 600.dp)).isEqualTo(NowPlayingLayout.Portrait)
    }
}
