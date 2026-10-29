package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class WaveformTilesTest {

    @Test
    fun `tiles are 256 pixels wide`() {
        assertThat(WaveformTiles.TILE_PX).isEqualTo(256)
        assertThat(WaveformTiles.startPx(3)).isEqualTo(768L)
    }

    @Test
    fun `a viewport inside one tile needs that tile`() {
        assertThat(WaveformTiles.tilesFor(leftPx = 10, widthPx = 20)).isEqualTo(0..0)
        assertThat(WaveformTiles.tilesFor(leftPx = 256, widthPx = 256)).isEqualTo(1..1)
    }

    @Test
    fun `a viewport on a tile boundary does not ask for the next tile`() {
        assertThat(WaveformTiles.tilesFor(leftPx = 0, widthPx = 512)).isEqualTo(0..1)
        assertThat(WaveformTiles.tilesFor(leftPx = 0, widthPx = 513)).isEqualTo(0..2)
    }

    @Test
    fun `a viewport that starts before the track reaches negative tiles`() {
        assertThat(WaveformTiles.tilesFor(leftPx = -1, widthPx = 2)).isEqualTo(-1..0)
        assertThat(WaveformTiles.tilesFor(leftPx = -540, widthPx = 1080)).isEqualTo(-3..2)
    }

    @Test
    fun `an empty viewport needs no tile`() {
        assertThat(WaveformTiles.tilesFor(leftPx = 100, widthPx = 0).isEmpty()).isTrue()
        assertThat(WaveformTiles.tilesFor(leftPx = 100, widthPx = -5).isEmpty()).isTrue()
    }

    @Test
    fun `for random viewports the tiles cover every pixel once with no gap or overlap`() {
        val random = Random(20261007)
        repeat(500) {
            val left = random.nextLong(-5_000, 2_000_000)
            val width = random.nextInt(1, 4_000)
            val tiles = WaveformTiles.tilesFor(left, width)
            assertThat(tiles.isEmpty()).isFalse()

            // Adjacent tiles meet exactly: no gap, no overlap.
            for (index in tiles.first until tiles.last) {
                assertThat(WaveformTiles.startPx(index) + WaveformTiles.TILE_PX).isEqualTo(WaveformTiles.startPx(index + 1))
            }
            // The first tile holds the first pixel and the last tile holds the last pixel.
            assertThat(WaveformTiles.startPx(tiles.first)).isAtMost(left)
            assertThat(WaveformTiles.startPx(tiles.first) + WaveformTiles.TILE_PX).isGreaterThan(left)
            val lastPx = left + width - 1
            assertThat(WaveformTiles.startPx(tiles.last)).isAtMost(lastPx)
            assertThat(WaveformTiles.startPx(tiles.last) + WaveformTiles.TILE_PX).isGreaterThan(lastPx)
            // No tile is wasted: each one intersects the viewport.
            for (index in tiles) {
                val start = WaveformTiles.startPx(index)
                assertThat(start + WaveformTiles.TILE_PX > left && start <= lastPx).isTrue()
            }
        }
    }

    @Test
    fun `the tile index of a pixel is its floor division`() {
        assertThat(WaveformTiles.indexOf(0)).isEqualTo(0)
        assertThat(WaveformTiles.indexOf(255)).isEqualTo(0)
        assertThat(WaveformTiles.indexOf(256)).isEqualTo(1)
        assertThat(WaveformTiles.indexOf(-1)).isEqualTo(-1)
        assertThat(WaveformTiles.indexOf(-256)).isEqualTo(-1)
        assertThat(WaveformTiles.indexOf(-257)).isEqualTo(-2)
    }
}
