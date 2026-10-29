package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class WaveformLagTest {

    private val reference: ByteArray = run {
        val random = Random(7)
        // A bumpy envelope: random levels smoothed over three columns.
        val raw = DoubleArray(3_000) { random.nextDouble(0.0, 255.0) }
        ByteArray(raw.size) { i ->
            val a = raw[maxOf(0, i - 1)]
            val b = raw[i]
            val c = raw[minOf(raw.size - 1, i + 1)]
            ((a + 2 * b + c) / 4).toInt().toByte()
        }
    }

    @Test
    fun `a plane that is late by three columns gives plus three`() {
        val ours = ByteArray(reference.size) { i -> if (i >= 3) reference[i - 3] else 0 }
        assertThat(WaveformLag.lagColumns(ours, reference, maxLag = 20)).isWithin(0.2).of(3.0)
    }

    @Test
    fun `a plane that is early gives a negative lag`() {
        val ours = ByteArray(reference.size) { i -> if (i + 2 < reference.size) reference[i + 2] else 0 }
        assertThat(WaveformLag.lagColumns(ours, reference, maxLag = 20)).isWithin(0.2).of(-2.0)
    }

    @Test
    fun `identical planes give zero`() {
        assertThat(WaveformLag.lagColumns(reference, reference, maxLag = 10)).isWithin(0.05).of(0.0)
    }

    @Test
    fun `a fractional delay of a smooth plane is resolved below one column`() {
        fun level(t: Double) = (127.5 + 100 * sin(t / 9.0) + 20 * sin(t / 2.3)).toInt().toByte()
        val ref = ByteArray(4_000) { level(it.toDouble()) }
        val ours = ByteArray(4_000) { level(it - 1.5) }
        assertThat(WaveformLag.lagColumns(ours, ref, maxLag = 10)).isWithin(0.3).of(1.5)
    }

    @Test
    fun `flat or empty planes give NaN`() {
        assertThat(WaveformLag.lagColumns(ByteArray(100), reference, maxLag = 5).isNaN()).isTrue()
        assertThat(WaveformLag.lagColumns(ByteArray(0), ByteArray(0), maxLag = 5).isNaN()).isTrue()
    }

    @Test
    fun `the lag in milliseconds uses 150 columns per second`() {
        assertThat(WaveformLag.columnsToMs(3.0)).isWithin(1e-9).of(20.0)
    }
}
