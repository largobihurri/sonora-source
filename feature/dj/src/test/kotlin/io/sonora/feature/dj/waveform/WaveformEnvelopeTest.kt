package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaveformEnvelopeTest {

    private val width = 1000

    private fun plane(columns: Int, loud: Map<Int, Int> = emptyMap()) =
        ByteArray(columns).also { loud.forEach { (c, v) -> it[c] = v.toByte() } }

    @Test
    fun `a single loud column survives every zoom`() {
        val column = 1_234
        val data = plane(columns = 6_000, loud = mapOf(column to 255))
        for (zoom in WaveformZoom.entries) {
            val pxPerMs = WaveformScale(width, zoom).pxPerMs
            val envelope = WaveformEnvelope.build(data, pxPerMs = pxPerMs)
            val loudPixels = (0 until envelope.pixelCount).filter { envelope.levelAt(it) == 255 }
            assertThat(loudPixels).isNotEmpty()
            // Every pixel that shows it covers the column's span of time.
            val columnStartMs = column * 1000.0 / 150
            val columnEndMs = (column + 1) * 1000.0 / 150
            for (px in loudPixels) {
                val pxStartMs = px / pxPerMs
                val pxEndMs = (px + 1) / pxPerMs
                assertThat(pxStartMs).isAtMost(columnEndMs)
                assertThat(pxEndMs).isAtLeast(columnStartMs)
            }
        }
    }

    @Test
    fun `when several columns share a pixel exactly one pixel shows the loud one`() {
        // 32 s zoom: 4.8 columns per pixel. Column 1234 has its centre at 8230 ms, pixel floor(8230 * 0.03125) = 257.
        val data = plane(columns = 6_000, loud = mapOf(1_234 to 255))
        val envelope = WaveformEnvelope.build(data, pxPerMs = 0.03125)
        val loudPixels = (0 until envelope.pixelCount).filter { envelope.levelAt(it) == 255 }
        assertThat(loudPixels).containsExactly(257)
        assertThat((0 until envelope.pixelCount).filter { it != 257 }.all { envelope.levelAt(it) == 0 }).isTrue()
    }

    @Test
    fun `a pixel shows the maximum of its columns and never an average`() {
        // 0.015 px/ms is 10 columns per pixel: pixel 0 covers all ten columns, the 200 among them wins.
        val data = byteArrayOf(10, 200.toByte(), 30, 40, 50, 60, 70, 80, 90, 100)
        val envelope = WaveformEnvelope.build(data, pxPerMs = 0.015)
        assertThat(envelope.pixelCount).isEqualTo(1)
        assertThat(envelope.levelAt(0)).isEqualTo(200)
    }

    @Test
    fun `levels are unsigned bytes`() {
        val data = byteArrayOf(0, 128.toByte(), 255.toByte(), 1)
        val envelope = WaveformEnvelope.build(data, pxPerMs = 0.15) // one pixel per column
        assertThat((0 until envelope.pixelCount).map { envelope.levelAt(it) }).containsExactly(0, 128, 255, 1).inOrder()
    }

    @Test
    fun `zoomed in beyond the column density the level is held across pixels`() {
        // pxPerMs 0.6 = 4 px per column (6.67 ms per column): every pixel of a column repeats its level.
        val data = byteArrayOf(10, 20, 30)
        val envelope = WaveformEnvelope.build(data, pxPerMs = 0.6)
        assertThat(envelope.pixelCount).isEqualTo(12)
        val levels = (0 until envelope.pixelCount).map { envelope.levelAt(it) }
        assertThat(levels).containsExactly(10, 10, 10, 10, 20, 20, 20, 20, 30, 30, 30, 30).inOrder()
    }

    @Test
    fun `the envelope covers the whole plane and no more`() {
        for (zoom in WaveformZoom.entries) {
            val pxPerMs = WaveformScale(width, zoom).pxPerMs
            val columns = 4_321
            val envelope = WaveformEnvelope.build(plane(columns, mapOf(columns - 1 to 200)), pxPerMs = pxPerMs)
            val durationMs = columns * 1000.0 / 150
            assertThat(envelope.pixelCount).isEqualTo(kotlin.math.ceil(durationMs * pxPerMs - 1e-9).toInt())
            assertThat(envelope.levelAt(envelope.pixelCount - 1)).isEqualTo(200)
        }
    }

    @Test
    fun `pixels outside the track are silent`() {
        val envelope = WaveformEnvelope.build(plane(300, mapOf(0 to 255, 299 to 255)), pxPerMs = 0.25)
        assertThat(envelope.levelAt(-1)).isEqualTo(0)
        assertThat(envelope.levelAt(envelope.pixelCount)).isEqualTo(0)
        assertThat(envelope.levelAt(Int.MAX_VALUE)).isEqualTo(0)
        assertThat(envelope.levelAt(0)).isEqualTo(255)
        assertThat(envelope.levelAt(envelope.pixelCount - 1)).isEqualTo(255)
    }

    @Test
    fun `an empty plane gives an empty envelope`() {
        val envelope = WaveformEnvelope.build(ByteArray(0), pxPerMs = 0.25)
        assertThat(envelope.pixelCount).isEqualTo(0)
        assertThat(envelope.levelAt(0)).isEqualTo(0)
    }

    @Test
    fun `a custom column density is honoured`() {
        // 100 columns per second, 10 ms each; 0.1 px/ms is one pixel per column.
        val envelope = WaveformEnvelope.build(byteArrayOf(5, 6, 7), columnsPerSecond = 100, pxPerMs = 0.1)
        assertThat((0 until envelope.pixelCount).map { envelope.levelAt(it) }).containsExactly(5, 6, 7).inOrder()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non positive scale is rejected`() {
        WaveformEnvelope.build(ByteArray(10), pxPerMs = 0.0)
    }
}
