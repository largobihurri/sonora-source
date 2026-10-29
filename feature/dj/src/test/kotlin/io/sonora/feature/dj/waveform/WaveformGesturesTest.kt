package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveformGesturesTest {

    // 1080 px for 8 s: 0.135 px per ms.
    private val pxPerMs = 0.135

    @Test
    fun `dragging the waveform left moves the playhead later`() {
        // 135 px is one second at this scale.
        assertThat(DragSeek.positionMs(startMs = 10_000.0, dragPx = -135.0, pxPerMs = pxPerMs, durationMs = 60_000.0))
            .isWithin(1e-9).of(11_000.0)
    }

    @Test
    fun `dragging the waveform right moves the playhead earlier`() {
        assertThat(DragSeek.positionMs(startMs = 10_000.0, dragPx = 270.0, pxPerMs = pxPerMs, durationMs = 60_000.0))
            .isWithin(1e-9).of(8_000.0)
    }

    @Test
    fun `a drag stops at the track start and end`() {
        assertThat(DragSeek.positionMs(startMs = 500.0, dragPx = 1_000.0, pxPerMs = pxPerMs, durationMs = 60_000.0))
            .isEqualTo(0.0)
        assertThat(DragSeek.positionMs(startMs = 59_900.0, dragPx = -1_000.0, pxPerMs = pxPerMs, durationMs = 60_000.0))
            .isEqualTo(60_000.0)
    }

    @Test
    fun `a drag with no scale or no duration keeps the clamped start`() {
        assertThat(DragSeek.positionMs(startMs = 5_000.0, dragPx = 100.0, pxPerMs = 0.0, durationMs = 60_000.0))
            .isEqualTo(5_000.0)
        assertThat(DragSeek.positionMs(startMs = 5_000.0, dragPx = 100.0, pxPerMs = pxPerMs, durationMs = 0.0))
            .isEqualTo(0.0)
        assertThat(DragSeek.positionMs(startMs = Double.NaN, dragPx = 0.0, pxPerMs = pxPerMs, durationMs = 60_000.0))
            .isEqualTo(0.0)
    }

    @Test
    fun `the released drag seeks to the rounded millisecond`() {
        assertThat(DragSeek.seekMs(1_234.5)).isEqualTo(1_235L)
        assertThat(DragSeek.seekMs(1_234.4)).isEqualTo(1_234L)
        assertThat(DragSeek.seekMs(Double.NaN)).isEqualTo(0L)
    }

    @Test
    fun `no pinch keeps the zoom`() {
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_8, scale = 1f)).isEqualTo(WaveformZoom.SECONDS_8)
    }

    @Test
    fun `spreading the fingers zooms in to fewer seconds`() {
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_8, scale = 2f)).isEqualTo(WaveformZoom.SECONDS_4)
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_32, scale = 4f)).isEqualTo(WaveformZoom.SECONDS_8)
    }

    @Test
    fun `pinching the fingers together zooms out to more seconds`() {
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_8, scale = 0.5f)).isEqualTo(WaveformZoom.SECONDS_16)
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_4, scale = 0.125f)).isEqualTo(WaveformZoom.SECONDS_32)
    }

    @Test
    fun `the zoom snaps to the nearest level on a log scale`() {
        // 8 s / 1.3 = 6.2 s: nearer 8 than 4 in ratio.
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_8, scale = 1.3f)).isEqualTo(WaveformZoom.SECONDS_8)
        // 8 s / 1.5 = 5.3 s: nearer 4.
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_8, scale = 1.5f)).isEqualTo(WaveformZoom.SECONDS_4)
        // 8 s / 0.7 = 11.4 s: 16 is 1.40 times that, 8 is 1.43 times less, so 16; 8 s / 0.75 = 10.7 s stays 8.
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_8, scale = 0.7f)).isEqualTo(WaveformZoom.SECONDS_16)
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_8, scale = 0.75f)).isEqualTo(WaveformZoom.SECONDS_8)
    }

    @Test
    fun `the zoom stays within its levels`() {
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_4, scale = 10f)).isEqualTo(WaveformZoom.SECONDS_4)
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_32, scale = 0.01f)).isEqualTo(WaveformZoom.SECONDS_32)
    }

    @Test
    fun `an invalid pinch scale keeps the zoom`() {
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_16, scale = 0f)).isEqualTo(WaveformZoom.SECONDS_16)
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_16, scale = -2f)).isEqualTo(WaveformZoom.SECONDS_16)
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_16, scale = Float.NaN)).isEqualTo(WaveformZoom.SECONDS_16)
        assertThat(ZoomSnap.snapped(WaveformZoom.SECONDS_16, scale = Float.POSITIVE_INFINITY))
            .isEqualTo(WaveformZoom.SECONDS_16)
    }

    @Test
    fun `a tap on the overview seeks to its share of the track`() {
        assertThat(OverviewSeek.msAt(xPx = 500.0, widthPx = 1_000.0, durationMs = 200_000L)).isEqualTo(100_000L)
        assertThat(OverviewSeek.msAt(xPx = 250.0, widthPx = 1_000.0, durationMs = 200_001L)).isEqualTo(50_000L)
    }

    @Test
    fun `a touch beyond the overview ends seeks to its ends`() {
        assertThat(OverviewSeek.msAt(xPx = -30.0, widthPx = 1_000.0, durationMs = 200_000L)).isEqualTo(0L)
        assertThat(OverviewSeek.msAt(xPx = 1_200.0, widthPx = 1_000.0, durationMs = 200_000L)).isEqualTo(200_000L)
    }

    @Test
    fun `an empty overview or an unknown duration seeks to the start`() {
        assertThat(OverviewSeek.msAt(xPx = 500.0, widthPx = 0.0, durationMs = 200_000L)).isEqualTo(0L)
        assertThat(OverviewSeek.msAt(xPx = 500.0, widthPx = 1_000.0, durationMs = 0L)).isEqualTo(0L)
        assertThat(OverviewSeek.msAt(xPx = 500.0, widthPx = 1_000.0, durationMs = -5L)).isEqualTo(0L)
    }
}
