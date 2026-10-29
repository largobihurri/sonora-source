package io.sonora.core.domain.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BlueWaveformTest {

    private fun waveform(
        detail: ByteArray = byteArrayOf(1, 2, 3),
        preview: ByteArray = ByteArray(BlueWaveform.PREVIEW_COLUMNS) { it.toByte() },
        tiny: ByteArray = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS) { (it % 16).toByte() },
    ) = BlueWaveform(detail, preview, tiny)

    @Test
    fun `columns pack height in the low five bits and whiteness in the high three`() {
        assertThat(BlueWaveform.packColumn(0, 0)).isEqualTo(0.toByte())
        assertThat(BlueWaveform.packColumn(31, 0)).isEqualTo(0x1F.toByte())
        assertThat(BlueWaveform.packColumn(0, 7)).isEqualTo(0xE0.toByte())
        assertThat(BlueWaveform.packColumn(31, 7)).isEqualTo(0xFF.toByte())
        assertThat(BlueWaveform.packColumn(17, 5)).isEqualTo((5 shl 5 or 17).toByte())
    }

    @Test
    fun `every packed column unpacks to its height and whiteness`() {
        for (height in 0..BlueWaveform.MAX_HEIGHT) {
            for (whiteness in 0..BlueWaveform.MAX_WHITENESS) {
                val column = BlueWaveform.packColumn(height, whiteness)
                assertThat(BlueWaveform.heightOf(column)).isEqualTo(height)
                assertThat(BlueWaveform.whitenessOf(column)).isEqualTo(whiteness)
            }
        }
    }

    @Test
    fun `packing rejects out of range values`() {
        assertThrows(IllegalArgumentException::class.java) { BlueWaveform.packColumn(32, 0) }
        assertThrows(IllegalArgumentException::class.java) { BlueWaveform.packColumn(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BlueWaveform.packColumn(0, 8) }
        assertThrows(IllegalArgumentException::class.java) { BlueWaveform.packColumn(0, -1) }
    }

    @Test
    fun `detail column count is 150 per second, keeping a partial last column`() {
        assertThat(BlueWaveform.detailColumnCount(44_100L * 150, 44_100)).isEqualTo(22_500)
        assertThat(BlueWaveform.detailColumnCount(44_100L * 150 + 1, 44_100)).isEqualTo(22_501)
        assertThat(BlueWaveform.detailColumnCount(48_000L * 10, 48_000)).isEqualTo(1_500)
        assertThat(BlueWaveform.detailColumnCount(293, 44_100)).isEqualTo(1)
        assertThat(BlueWaveform.detailColumnCount(0, 44_100)).isEqualTo(0)
        // 22.05 kHz: 147 frames per column; 32 kHz: 213.33 frames per column.
        assertThat(BlueWaveform.detailColumnCount(147, 22_050)).isEqualTo(1)
        assertThat(BlueWaveform.detailColumnCount(148, 22_050)).isEqualTo(2)
        assertThat(BlueWaveform.detailColumnCount(32_000, 32_000)).isEqualTo(150)
    }

    @Test
    fun `detail columns start at whole frames sr over 150 apart`() {
        assertThat(BlueWaveform.detailColumnStartFrame(0, 44_100)).isEqualTo(0)
        assertThat(BlueWaveform.detailColumnStartFrame(1, 44_100)).isEqualTo(294)
        assertThat(BlueWaveform.detailColumnStartFrame(1, 48_000)).isEqualTo(320)
        assertThat(BlueWaveform.detailColumnStartFrame(1, 32_000)).isEqualTo(213)
        assertThat(BlueWaveform.detailColumnStartFrame(2, 32_000)).isEqualTo(426)
        assertThat(BlueWaveform.detailColumnStartFrame(3, 32_000)).isEqualTo(640)
    }

    @Test
    fun `contents are copied in and out`() {
        val detail = byteArrayOf(1, 2, 3)
        val subject = waveform(detail = detail)
        detail[0] = 99
        subject.detail()[1] = 99
        subject.preview()[0] = 99
        subject.tinyPreview()[0] = 9

        assertThat(subject.detail()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(subject.detailSize).isEqualTo(3)
        assertThat(subject.preview()[0]).isEqualTo(0.toByte())
        assertThat(subject.tinyPreview()[0]).isEqualTo(0.toByte())
    }

    @Test
    fun `equality and hash code follow the contents`() {
        val a = waveform()
        val b = waveform()
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(waveform(detail = byteArrayOf(1, 2, 4)))
        assertThat(a).isNotEqualTo(waveform(preview = ByteArray(BlueWaveform.PREVIEW_COLUMNS)))
        assertThat(a).isNotEqualTo(waveform(tiny = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS)))
        // Same bytes in a different role are a different waveform.
        assertThat(waveform(detail = ByteArray(0))).isNotEqualTo(a)
        assertThat(a.toString()).contains("detail=3")
    }

    @Test
    fun `preview sizes and tiny heights are enforced`() {
        assertThrows(IllegalArgumentException::class.java) { waveform(preview = ByteArray(399)) }
        assertThrows(IllegalArgumentException::class.java) { waveform(tiny = ByteArray(101)) }
        assertThrows(IllegalArgumentException::class.java) {
            waveform(tiny = ByteArray(BlueWaveform.TINY_PREVIEW_COLUMNS).also { it[5] = 16 })
        }
        // An empty detail is valid (a zero-length track).
        assertThat(waveform(detail = ByteArray(0)).detailSize).isEqualTo(0)
    }

    @Test
    fun `track analysis carries the waveform and compares it by content`() {
        val grid = BeatGrid(128.0, 12.0, 1, 10)
        val a = TrackAnalysis(grid, MusicalKey.A_MINOR, waveform(), 5_000, "test")
        val b = TrackAnalysis(grid, MusicalKey.A_MINOR, waveform(), 5_000, "test")
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a.waveform.detailSize).isEqualTo(3)
    }
}
