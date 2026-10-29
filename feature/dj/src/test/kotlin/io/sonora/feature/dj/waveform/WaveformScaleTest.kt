package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveformScaleTest {

    @Test
    fun `the zooms show 4 8 16 and 32 seconds and 8 is the default`() {
        assertThat(WaveformZoom.entries.map { it.visibleSeconds }).containsExactly(4, 8, 16, 32).inOrder()
        assertThat(WaveformZoom.DEFAULT.visibleSeconds).isEqualTo(8)
        assertThat(WaveformZoom.SECONDS_8.visibleMs).isEqualTo(8_000.0)
    }

    @Test
    fun `the whole width holds the visible span at every zoom`() {
        for (zoom in WaveformZoom.entries) {
            val scale = WaveformScale(widthPx = 1080, zoom = zoom)
            assertThat(scale.absolutePx(zoom.visibleMs)).isWithin(1e-9).of(1080.0)
            assertThat(scale.pxPerMs * zoom.visibleMs).isWithin(1e-9).of(1080.0)
        }
    }

    @Test
    fun `absolute pixels are anchored to the track start`() {
        for (zoom in WaveformZoom.entries) {
            val scale = WaveformScale(widthPx = 1000, zoom = zoom)
            assertThat(scale.absolutePx(0.0)).isEqualTo(0.0)
            // Scrolling is a translation: the distance between two instants never depends on where they are.
            val near = scale.absolutePx(1_500.0) - scale.absolutePx(1_000.0)
            val far = scale.absolutePx(301_500.0) - scale.absolutePx(301_000.0)
            assertThat(far).isWithin(1e-6).of(near)
        }
    }

    @Test
    fun `ms to pixel to ms round trips at every zoom`() {
        val instants = doubleArrayOf(0.0, 1.0, 333.3, 8_000.0, 61_234.5, 599_999.9, 3_600_000.0)
        for (zoom in WaveformZoom.entries) {
            for (width in intArrayOf(1, 360, 1080, 2400)) {
                val scale = WaveformScale(widthPx = width, zoom = zoom)
                for (ms in instants) {
                    assertThat(scale.msAt(scale.absolutePx(ms))).isWithin(1e-6).of(ms)
                }
            }
        }
    }

    @Test
    fun `pixel to ms to pixel round trips at every zoom`() {
        for (zoom in WaveformZoom.entries) {
            val scale = WaveformScale(widthPx = 1080, zoom = zoom)
            for (px in doubleArrayOf(-540.0, 0.0, 12.5, 540.0, 100_000.0)) {
                assertThat(scale.absolutePx(scale.msAt(px))).isWithin(1e-6).of(px)
            }
        }
    }

    @Test
    fun `zooming out shows more time per pixel`() {
        val scales = WaveformZoom.entries.map { WaveformScale(1000, it) }
        assertThat(scales.map { it.pxPerMs }).isInOrder(Comparator<Double> { a, b -> b.compareTo(a) })
        assertThat(scales.map { it.pxPerMs }).containsExactly(0.25, 0.125, 0.0625, 0.03125).inOrder()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero width is rejected`() {
        WaveformScale(widthPx = 0, zoom = WaveformZoom.DEFAULT)
    }

    @Test
    fun `the playhead centre pixel gives the leftmost visible pixel`() {
        val scale = WaveformScale(widthPx = 1000, zoom = WaveformZoom.SECONDS_8)
        // Playhead at the centre: at 10 s the window starts 4 s earlier, which is 500 px left of the playhead.
        assertThat(scale.leftPxFor(positionMs = 10_000.0)).isWithin(1e-9).of(scale.absolutePx(10_000.0) - 500.0)
    }
}
