package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BandWaveformTest {

    private fun plane(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    @Test
    fun `150 columns make a second`() {
        assertThat(BandWaveform.COLUMNS_PER_SECOND).isEqualTo(150)
    }

    @Test
    fun `the four planes have the same length`() {
        val waveform = BandWaveform(plane(300, 1), plane(300, 2), plane(300, 3), plane(300, 4), version = 1)

        assertThat(waveform.columnCount).isEqualTo(300)
        assertThat(waveform.durationMs).isEqualTo(2000.0)
        assertThat(waveform.low()).isEqualTo(plane(300, 1))
        assertThat(waveform.mid()).isEqualTo(plane(300, 2))
        assertThat(waveform.high()).isEqualTo(plane(300, 3))
        assertThat(waveform.peak()).isEqualTo(plane(300, 4))
    }

    @Test
    fun `planes of different lengths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BandWaveform(plane(10, 0), plane(11, 0), plane(10, 0), plane(10, 0), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BandWaveform(plane(10, 0), plane(10, 0), plane(9, 0), plane(10, 0), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BandWaveform(plane(10, 0), plane(10, 0), plane(10, 0), plane(0, 0), 1)
        }
    }

    @Test
    fun `the version is positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            BandWaveform(plane(1, 0), plane(1, 0), plane(1, 0), plane(1, 0), 0)
        }
    }

    @Test
    fun `an empty waveform is valid`() {
        val waveform = BandWaveform(ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0), 1)

        assertThat(waveform.columnCount).isEqualTo(0)
        assertThat(waveform.durationMs).isEqualTo(0.0)
    }

    @Test
    fun `the planes are copied in and out`() {
        val low = plane(4, 7)
        val waveform = BandWaveform(low, plane(4, 0), plane(4, 0), plane(4, 0), 1)

        low[0] = 99
        waveform.low()[1] = 99

        assertThat(waveform.low()).isEqualTo(plane(4, 7))
    }

    @Test
    fun `equality compares the contents and the version`() {
        fun make(v: Int, fill: Int = 1) = BandWaveform(plane(3, fill), plane(3, 2), plane(3, 3), plane(3, 4), v)

        assertThat(make(1)).isEqualTo(make(1))
        assertThat(make(1).hashCode()).isEqualTo(make(1).hashCode())
        assertThat(make(1)).isNotEqualTo(make(2))
        assertThat(make(1)).isNotEqualTo(make(1, fill = 9))
    }
}
