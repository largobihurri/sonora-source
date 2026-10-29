package io.sonora.core.audiodsp.preamp

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.audiodsp.fft.RealFft
import io.sonora.core.audiodsp.filter.FilterProbe
import io.sonora.core.audiodsp.resample.Signals
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sqrt

class FiveWayPreampTest {

    private fun preamp(rateHz: Int = 48_000, channels: Int = 1) = FiveWayPreamp().apply { prepare(rateHz, channels) }

    private fun FiveWayPreamp.run(buffer: FloatArray, channels: Int = 1, from: Int = 0, to: Int = buffer.size / channels) {
        var start = from
        while (start < to) {
            val n = minOf(480, to - start)
            process(buffer, start * channels, n)
            start += n
        }
    }

    /** Magnitude in dB of the preamp's impulse response, per FFT bin, with its frequency. */
    private fun magnitudeDb(rateHz: Int, size: Int, configure: FiveWayPreamp.() -> Unit = {}): List<Pair<Double, Double>> {
        val p = preamp(rateHz).apply(configure)
        val impulse = FloatArray(size).also { it[0] = 1f }
        p.run(impulse)
        val fft = RealFft(size)
        val re = FloatArray(fft.binCount)
        val im = FloatArray(fft.binCount)
        fft.forward(impulse, re, im)
        return (0 until fft.binCount).map { k ->
            k.toDouble() * rateHz / size to 20 * log10(hypot(re[k].toDouble(), im[k].toDouble()))
        }
    }

    private fun assertFlat(rateHz: Int, size: Int, toleranceDb: Double) {
        val band = magnitudeDb(rateHz, size).filter { (hz, _) -> hz in 30.0..18_000.0 }
        assertThat(band.size).isGreaterThan(1000)
        for ((hz, db) in band) {
            assertWithMessage("%s Hz", hz).that(db).isWithin(toleranceDb).of(0.0)
        }
    }

    private fun gainDb(frequencyHz: Double, configure: FiveWayPreamp.() -> Unit): Double {
        val p = preamp().apply(configure)
        return FilterProbe.gainDb(48_000, frequencyHz) { p.run(it) }
    }

    private fun only(way: Int): FiveWayPreamp.() -> Unit = {
        for (w in 0 until FiveWayPreamp.WAY_COUNT) setKilled(w, w != way)
        settle()
    }

    /** Runs silence long enough for every ramp to finish. */
    private fun FiveWayPreamp.settle() = run(FloatArray(4800))

    @Test
    fun `the defaults are the five design ways`() {
        val high = (0 until 5).map { FiveWayPreamp.defaultHighPassHz(it) }
        val low = (0 until 5).map { FiveWayPreamp.defaultLowPassHz(it) }
        assertThat(high).containsExactly(20f, 80f, 250f, 1200f, 5000f).inOrder()
        assertThat(low).containsExactly(80f, 250f, 1200f, 5000f, 20_000f).inOrder()
        val p = preamp()
        for (w in 0 until 5) {
            assertThat(p.highPassHz(w)).isEqualTo(high[w])
            assertThat(p.lowPassHz(w)).isEqualTo(low[w])
        }
        assertThat(p.dropWays).isEqualTo(0b00011)
    }

    @Test
    fun `the default ways sum flat within 0_1 dB from 30 Hz to 18 kHz`() {
        assertFlat(48_000, 65_536, 0.1)
    }

    @Test
    fun `the default ways sum flat at 44_1 and 96 kHz too`() {
        assertFlat(44_100, 65_536, 0.1)
        assertFlat(96_000, 131_072, 0.1)
    }

    @Test
    fun `a single way follows its own corners at 24 dB per octave`() {
        val centre = sqrt(250.0 * 1200.0)
        // Both LR4 corners are 0.37 dB down at the geometric centre of this 250 Hz - 1.2 kHz way.
        assertThat(gainDb(centre, only(2))).isWithin(0.05).of(-0.74)
        assertThat(gainDb(250.0, only(2))).isWithin(0.3).of(-6.0)
        assertThat(gainDb(1200.0, only(2))).isWithin(0.3).of(-6.0)
        assertThat(gainDb(250.0 / 4, only(2))).isWithin(1.0).of(-48.0)
    }

    @Test
    fun `corners at their range ends are a true bypass`() {
        val band = magnitudeDb(48_000, 65_536) {
            setHighPassHz(0, FiveWayPreamp.MIN_CORNER_HZ)
            setLowPassHz(0, FiveWayPreamp.MAX_CORNER_HZ)
            for (w in 1 until 5) setKilled(w, true)
            settle()
        }.filter { (hz, _) -> hz in 20.0..20_000.0 }
        // The phase-compensation allpasses leave the magnitude alone; a filter at 20 Hz or 20 kHz would not.
        for ((hz, db) in band) assertWithMessage("%s Hz", hz).that(db).isWithin(0.001).of(0.0)
    }

    @Test
    fun `a killed way is at least 90 dB down at its centre`() {
        val rate = 48_000
        val centre = sqrt(250.0 * 1200.0)
        val p = preamp(rate).apply(only(2))
        val signal = Signals.sines(rate, rate, 0.5, centre)
        val half = rate / 2
        p.run(signal, to = half)
        p.setKilled(2, true)
        p.run(signal, from = half)
        val out = Signals.channel(signal, 1, 0)

        val open = Signals.amplitudeAt(out, half - 4800, half, centre, rate)
        val killed = Signals.amplitudeAt(out, half + 480, rate, centre, rate)
        assertThat(Signals.db(open / 0.5)).isWithin(0.05).of(-0.74)
        assertThat(killed / open).isAtMost(3.2e-5) // -90 dB
    }

    @Test
    fun `killing a way removes its share of the mix and leaves the others`() {
        val rate = 48_000
        val input = Random(5).let { r -> FloatArray(rate) { r.nextFloat() - 0.5f } }
        val killed = input.copyOf()
        val zeroFader = input.copyOf()
        preamp(rate).apply { setKilled(1, true); settle() }.run(killed)
        preamp(rate).apply { setFaderGain(1, 0f); settle() }.run(zeroFader)
        assertThat(killed).isEqualTo(zeroFader)
    }

    @Test
    fun `the kill ramps over 5 ms`() {
        val rate = 48_000
        val p = preamp(rate).apply(only(2))
        val signal = Signals.sines(rate, rate / 2, 0.5, 600.0)
        p.run(signal, to = rate / 4)
        p.setKilled(2, true)
        p.process(signal, rate / 4, rate / 4)
        val ramp = rate * 5 / 1000
        val during = Signals.amplitudeAt(Signals.channel(signal, 1, 0), rate / 4 + ramp / 4, rate / 4 + ramp / 2, 600.0, rate)
        assertThat(during).isGreaterThan(0.05)
        // After the ramp only the short ring of the 5 kHz compensation allpass is left; 1 ms later it is gone.
        for (n in rate / 4 + ramp + rate / 1000 until rate / 2) assertThat(abs(signal[n])).isAtMost(0.5f * 3.2e-5f)
    }

    @Test
    fun `a kill toggle does not click`() {
        val rate = 48_000
        val input = Signals.sines(rate, rate, 0.5, 600.0)
        val curvature = FilterProbe.maxSecondDifference(input, 0, input.size)
        val p = preamp(rate)
        val out = input.copyOf()
        p.run(out, to = rate / 4)
        p.setKilled(2, true)
        p.run(out, from = rate / 4, to = rate / 2)
        p.setKilled(2, false)
        p.run(out, from = rate / 2)
        assertThat(FilterProbe.maxSecondDifference(out, rate / 8, rate)).isAtMost(curvature * 1.2 + 1e-4)
    }

    @Test
    fun `moving a corner during a sine produces no step`() {
        val rate = 48_000
        val input = Signals.sines(rate, rate, 0.5, 600.0)
        val curvature = FilterProbe.maxSecondDifference(input, 0, input.size)
        val p = preamp(rate)
        val out = input.copyOf()
        p.run(out, to = rate / 4)
        p.setHighPassHz(2, 4000f) // way 3 jumps from 250 Hz to 4 kHz: its band closes
        p.setLowPassHz(1, 2000f) // way 2 opens up to 2 kHz
        p.run(out, from = rate / 4, to = rate / 2)
        p.setHighPassHz(2, 250f)
        p.setLowPassHz(1, 250f)
        p.setLowPassHz(4, 1000f) // from off (20 kHz) to an active corner
        p.setHighPassHz(0, 300f) // from off (20 Hz) to an active corner
        p.run(out, from = rate / 2)
        assertThat(FilterProbe.maxSecondDifference(out, rate / 8, rate)).isAtMost(curvature * 1.2 + 1e-4)
    }

    @Test
    fun `a corner move reaches its new response after 20 ms`() {
        val p = preamp().apply(only(2))
        p.setLowPassHz(2, 600f)
        p.run(FloatArray(960 + 10))
        assertThat(FilterProbe.gainDb(48_000, 600.0) { p.run(it) }).isWithin(0.1).of(-6.28)
        assertThat(p.lowPassHz(2)).isEqualTo(600f)
    }

    @Test
    fun `the fader detent maps to unity and the fader spans -inf to +6 dB`() {
        assertThat(PreampFader.gainForPosition(PreampFader.DETENT_POSITION)).isEqualTo(1f)
        assertThat(PreampFader.gainForPosition(PreampFader.DETENT_POSITION + PreampFader.DETENT_HALF_WIDTH * 0.9f)).isEqualTo(1f)
        assertThat(PreampFader.gainForPosition(PreampFader.DETENT_POSITION - PreampFader.DETENT_HALF_WIDTH * 0.9f)).isEqualTo(1f)
        assertThat(PreampFader.gainForPosition(0f)).isEqualTo(0f)
        assertThat(20 * log10(PreampFader.gainForPosition(1f).toDouble())).isWithin(1e-4).of(6.0)
        var last = -1f
        for (i in 0..1000) {
            val g = PreampFader.gainForPosition(i / 1000f)
            assertThat(g).isAtLeast(last)
            last = g
        }
        assertThat(PreampFader.positionForGain(1f)).isEqualTo(PreampFader.DETENT_POSITION)
        assertThat(PreampFader.gainForPosition(PreampFader.positionForGain(0.25f))).isWithin(1e-4f).of(0.25f)
    }

    @Test
    fun `a way at the fader detent is at unity and +6 dB is +6 dB`() {
        val centre = sqrt(250.0 * 1200.0)
        val unity = gainDb(centre) { only(2).invoke(this); setFaderGain(2, PreampFader.gainForPosition(PreampFader.DETENT_POSITION)); settle() }
        val boosted = gainDb(centre) { only(2).invoke(this); setFaderGain(2, PreampFader.gainForPosition(1f)); settle() }
        assertThat(boosted - unity).isWithin(0.01).of(6.0)
        assertThat(gainDb(1000.0) { settle() }).isWithin(0.1).of(0.0)
    }

    @Test
    fun `DROP kills ways 1 and 2 by default and the set is configurable`() {
        val rate = 48_000
        val input = Random(11).let { r -> FloatArray(rate) { r.nextFloat() - 0.5f } }
        fun render(configure: FiveWayPreamp.() -> Unit) = input.copyOf().also { preamp(rate).apply { configure(); settle() }.run(it) }

        val dropped = render { setDropActive(true) }
        val killed = render { setKilled(0, true); setKilled(1, true) }
        assertThat(dropped).isEqualTo(killed)

        val customDrop = render { setDropWays(0b00100); setDropActive(true) }
        val custom = render { setKilled(2, true) }
        assertThat(customDrop).isEqualTo(custom)

        val released = render { setDropActive(true); settle(); setDropActive(false) }
        assertThat(released).isEqualTo(render { })
    }

    @Test
    fun `stereo channels are processed separately`() {
        val rate = 48_000
        val p = preamp(rate, channels = 2)
        val buffer = Signals.sines(rate, rate / 4, 0.5, 300.0, 0.0)
        p.run(buffer, channels = 2)
        for (n in 0 until rate / 4) assertThat(buffer[2 * n + 1]).isEqualTo(0f)
        val left = Signals.channel(buffer, 2, 0)
        assertThat(Signals.db(Signals.amplitudeAt(left, rate / 8, rate / 4, 300.0, rate) / 0.5)).isWithin(0.1).of(0.0)
    }

    @Test
    fun `non-finite input becomes silence and never escapes`() {
        val p = preamp()
        val buffer = FloatArray(480) { if (it % 2 == 0) Float.NaN else Float.POSITIVE_INFINITY }
        p.process(buffer, 0, 480)
        for (v in buffer) assertThat(v.isFinite()).isTrue()
        val after = FloatArray(480) { 0.3f }
        p.process(after, 0, 480)
        for (v in after) assertThat(v.isFinite()).isTrue()
    }

    @Test
    fun `processing a buffer allocates nothing`() {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val p = preamp(48_000, channels = 2)
        val buffer = Random(3).let { r -> FloatArray(2 * 480) { r.nextFloat() - 0.5f } }
        var flip = false
        val step = {
            flip = !flip
            p.setLowPassHz(1, if (flip) 300f else 250f)
            p.setKilled(3, flip)
            p.setDropActive(!flip)
            p.setFaderGain(0, if (flip) 0.5f else 1f)
            p.process(buffer, 0, 480)
        }
        repeat(5000) { step() } // warm up the JIT
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        repeat(500) { step() }
        assertThat(threads.getThreadAllocatedBytes(id) - before).isLessThan(4096L)
    }

    @Test
    fun `the fused loop gives bit for bit what the per-sample preamp gave, through glides, fades, faders and kills`() {
        for (channels in 1..2) {
            val random = Random(7L + channels)
            val frames = 48_000
            val input = FloatArray(frames * channels) { (random.nextGaussian() * 0.3).toFloat() }
            input[1234 * channels] = Float.NaN
            val fused = input.copyOf()
            val expected = input.copyOf()
            val preamp = preamp(channels = channels)
            val reference = ReferenceFiveWayPreamp().apply { prepare(48_000, channels) }
            var start = 0
            var step = 0
            while (start < frames) {
                // Every few calls a move: corners glide, a corner turns on or off, a fader ramps, a way is killed, DROP.
                when (step % 9) {
                    1 -> for (w in 0 until FiveWayPreamp.WAY_COUNT) {
                        preamp.setHighPassHz(w, 40f + 300f * w)
                        reference.setHighPassHz(w, 40f + 300f * w)
                    }
                    3 -> { preamp.setLowPassHz(4, 9000f); reference.setLowPassHz(4, 9000f) }
                    5 -> { preamp.setFaderGain(2, 1.7f); reference.setFaderGain(2, 1.7f) }
                    6 -> { preamp.setKilled(1, step % 2 == 0); reference.setKilled(1, step % 2 == 0) }
                    7 -> { preamp.setDropActive(step % 4 == 3); reference.setDropActive(step % 4 == 3) }
                    8 -> {
                        val hz = if (step % 2 == 0) FiveWayPreamp.MIN_CORNER_HZ else 30f
                        preamp.setHighPassHz(0, hz)
                        reference.setHighPassHz(0, hz)
                    }
                }
                val n = minOf(intArrayOf(1, 37, 512, 1500, 96)[step % 5], frames - start)
                preamp.process(fused, start * channels, n)
                reference.process(expected, start * channels, n)
                start += n
                step++
            }
            assertWithMessage("channels $channels").that(fused).isEqualTo(expected)
        }
    }
}
