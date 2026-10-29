package io.sonora.core.audiodsp.filter

import com.google.common.truth.Truth.assertThat
import io.sonora.core.audiodsp.filter.LinkwitzRiley4.Type.HIGH_PASS
import io.sonora.core.audiodsp.filter.LinkwitzRiley4.Type.LOW_PASS
import org.junit.Test

class LinkwitzRiley4Test {

    private fun filter(type: LinkwitzRiley4.Type, cutoffHz: Double, rateHz: Int) =
        LinkwitzRiley4(type, channels = 1).apply { setCutoff(cutoffHz, rateHz.toDouble()) }

    private fun gainDb(type: LinkwitzRiley4.Type, cutoffHz: Double, rateHz: Int, frequencyHz: Double): Double {
        val lr = filter(type, cutoffHz, rateHz)
        return FilterProbe.gainDb(rateHz, frequencyHz) { buf ->
            for (i in buf.indices) buf[i] = lr.process(buf[i].toDouble(), 0).toFloat()
        }
    }

    @Test
    fun `low-pass and high-pass are 6 dB down at the corner`() {
        assertThat(gainDb(LOW_PASS, 1000.0, 48_000, 1000.0)).isWithin(0.03).of(-6.02)
        assertThat(gainDb(HIGH_PASS, 1000.0, 48_000, 1000.0)).isWithin(0.03).of(-6.02)
    }

    @Test
    fun `the slope is 24 dB per octave`() {
        val hpQuarter = gainDb(HIGH_PASS, 1000.0, 48_000, 250.0)
        val hpEighth = gainDb(HIGH_PASS, 1000.0, 48_000, 125.0)
        assertThat(hpQuarter - hpEighth).isWithin(0.3).of(24.0)

        // Far enough below Nyquist for the bilinear warping to stay out of the way.
        val lpFour = gainDb(LOW_PASS, 250.0, 48_000, 1000.0)
        val lpEight = gainDb(LOW_PASS, 250.0, 48_000, 2000.0)
        assertThat(lpFour - lpEight).isWithin(0.3).of(24.0)
    }

    @Test
    fun `the pass band is flat`() {
        assertThat(gainDb(LOW_PASS, 1000.0, 48_000, 100.0)).isWithin(0.01).of(0.0)
        assertThat(gainDb(HIGH_PASS, 1000.0, 48_000, 10_000.0)).isWithin(0.01).of(0.0)
    }

    @Test
    fun `low-pass plus high-pass at the same corner sums flat`() {
        for (frequency in listOf(100.0, 500.0, 1000.0, 2000.0, 8000.0)) {
            val lp = filter(LOW_PASS, 1000.0, 48_000)
            val hp = filter(HIGH_PASS, 1000.0, 48_000)
            val gain = FilterProbe.gainDb(48_000, frequency) { buf ->
                for (i in buf.indices) {
                    val x = buf[i].toDouble()
                    buf[i] = (lp.process(x, 0) + hp.process(x, 0)).toFloat()
                }
            }
            assertThat(gain).isWithin(0.01).of(0.0)
        }
    }

    @Test
    fun `the response is the same at 44_1, 48 and 96 kHz`() {
        for (type in listOf(LOW_PASS, HIGH_PASS)) {
            val reference = listOf(500.0, 1000.0, 2000.0).map { gainDb(type, 1000.0, 48_000, it) }
            for (rate in listOf(44_100, 96_000)) {
                val gains = listOf(500.0, 1000.0, 2000.0).map { gainDb(type, 1000.0, rate, it) }
                assertThat(gains[0]).isWithin(0.2).of(reference[0])
                assertThat(gains[1]).isWithin(0.03).of(reference[1])
                assertThat(gains[2]).isWithin(0.2).of(reference[2])
            }
        }
    }

    @Test
    fun `channels keep separate state`() {
        val lr = LinkwitzRiley4(LOW_PASS, channels = 2).apply { setCutoff(500.0, 48_000.0) }
        val mono = filter(LOW_PASS, 500.0, 48_000)
        for (n in 0 until 2000) {
            val x = if (n % 50 == 0) 1.0 else 0.0
            val left = lr.process(x, 0)
            val right = lr.process(0.0, 1)
            assertThat(left).isEqualTo(mono.process(x, 0))
            assertThat(right).isEqualTo(0.0)
        }
    }

    @Test
    fun `non-finite input is treated as silence`() {
        val lr = filter(HIGH_PASS, 200.0, 48_000)
        for (n in 0 until 100) lr.process(if (n % 2 == 0) Double.NaN else Double.POSITIVE_INFINITY, 0)
        for (n in 0 until 100) assertThat(lr.process(0.5, 0).isFinite()).isTrue()
    }

    @Test
    fun `tiny state decays to exact zero instead of denormals`() {
        val lr = filter(LOW_PASS, 100.0, 48_000)
        lr.process(1e-3, 0)
        var last = 1.0
        repeat(200_000) { last = lr.process(0.0, 0) }
        assertThat(last).isEqualTo(0.0)
    }
}
