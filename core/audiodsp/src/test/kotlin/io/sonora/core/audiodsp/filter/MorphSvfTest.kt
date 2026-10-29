package io.sonora.core.audiodsp.filter

import com.google.common.truth.Truth.assertThat
import io.sonora.core.audiodsp.resample.Signals
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class MorphSvfTest {

    private fun noise(samples: Int, seed: Long) = Random(seed).let { r -> FloatArray(samples) { r.nextFloat() * 2f - 1f } }

    private fun morph(rateHz: Int, channels: Int = 1, x: Float, y: Float = 0f) =
        MorphSvf().apply {
            prepare(rateHz, channels)
            setPosition(x, y)
        }

    /** Processes in buffers of 480 frames, as the sink would. */
    private fun MorphSvf.run(buffer: FloatArray, channels: Int = 1) {
        val frames = buffer.size / channels
        var start = 0
        while (start < frames) {
            val n = minOf(480, frames - start)
            process(buffer, start * channels, n)
            start += n
        }
    }

    private fun gainDb(rateHz: Int, x: Float, y: Float, frequencyHz: Double, amplitude: Double = 0.25): Double {
        val filter = morph(rateHz, x = x, y = y)
        return FilterProbe.gainDb(rateHz, frequencyHz, amplitude) { filter.run(it) }
    }

    @Test
    fun `the centre detent is a bit-exact bypass`() {
        for (channels in 1..2) {
            val filter = morph(48_000, channels, x = 0f, y = 1f)
            val input = noise(48_000 * channels, seed = channels.toLong())
            val output = input.copyOf()
            filter.run(output, channels)
            assertThat(output).isEqualTo(input)
            assertThat(filter.isBypassed).isTrue()
        }
    }

    @Test
    fun `a small offset inside the detent is still a bit-exact bypass`() {
        val filter = morph(48_000, x = MorphSvf.DETENT_HALF_WIDTH * 0.9f, y = 0.5f)
        val input = noise(9600, seed = 7)
        val output = input.copyOf()
        filter.run(output)
        assertThat(output).isEqualTo(input)
    }

    @Test
    fun `returning to the detent fades into a bit-exact bypass`() {
        val filter = morph(48_000, channels = 2, x = -0.6f, y = 0.3f)
        val first = noise(2 * 9600, seed = 1)
        filter.run(first, 2)
        assertThat(filter.isBypassed).isFalse()

        filter.setPosition(0f, 0.3f)
        filter.run(noise(2 * 14_400, seed = 2), 2) // 300 ms: the glide plus the 10 ms crossfade
        val input = noise(2 * 4800, seed = 3)
        val output = input.copyOf()
        filter.run(output, 2)

        assertThat(output).isEqualTo(input)
        assertThat(filter.isBypassed).isTrue()
    }

    @Test
    fun `the edges map to a 200 Hz low-pass and a 6 kHz high-pass on a log scale`() {
        assertThat(MorphSvf.cutoffHz(-1f)).isWithin(1e-6).of(200.0)
        assertThat(MorphSvf.cutoffHz(1f)).isWithin(1e-6).of(6000.0)
        assertThat(MorphSvf.cutoffHz(0f).isNaN()).isTrue()
        // Log map: equal steps of X give equal frequency ratios.
        val a = MorphSvf.cutoffHz(-0.4f)
        val b = MorphSvf.cutoffHz(-0.6f)
        val c = MorphSvf.cutoffHz(-0.8f)
        assertThat(a / b).isWithin(1e-5).of(b / c)
        assertThat(MorphSvf.cutoffHz(0.8f)).isGreaterThan(MorphSvf.cutoffHz(0.4f))
    }

    @Test
    fun `Y maps the resonance from Q 0_7 to Q 8`() {
        assertThat(MorphSvf.resonanceQ(0f)).isWithin(1e-6).of(1 / sqrt(2.0))
        assertThat(MorphSvf.resonanceQ(1f)).isWithin(1e-6).of(8.0)
        assertThat(MorphSvf.resonanceQ(0.5f)).isWithin(1e-6).of(sqrt(8.0 / sqrt(2.0)))
    }

    @Test
    fun `the low-pass side is 3 dB down at its cutoff with no resonance`() {
        val fc = MorphSvf.cutoffHz(-0.5f)
        assertThat(gainDb(48_000, -0.5f, 0f, fc)).isWithin(0.1).of(-3.01)
        assertThat(gainDb(48_000, -0.5f, 0f, fc / 8)).isWithin(0.05).of(0.0)
        assertThat(gainDb(48_000, -0.5f, 0f, fc * 4)).isLessThan(-20.0)
    }

    @Test
    fun `the high-pass side is 3 dB down at its cutoff with no resonance`() {
        val fc = MorphSvf.cutoffHz(0.5f)
        assertThat(gainDb(48_000, 0.5f, 0f, fc)).isWithin(0.1).of(-3.01)
        assertThat(gainDb(48_000, 0.5f, 0f, fc * 8)).isWithin(0.05).of(0.0)
        assertThat(gainDb(48_000, 0.5f, 0f, fc / 4)).isLessThan(-20.0)
    }

    @Test
    fun `full resonance peaks at Q at the cutoff`() {
        val fc = MorphSvf.cutoffHz(-0.5f)
        assertThat(gainDb(48_000, -0.5f, 1f, fc, amplitude = 0.01)).isWithin(0.3).of(20 * log10(8.0))
    }

    @Test
    fun `the response is the same at 44_1, 48 and 96 kHz`() {
        for (x in listOf(-0.5f, 0.5f)) {
            val fc = MorphSvf.cutoffHz(x)
            val reference = listOf(fc / 2, fc, fc * 2).map { gainDb(48_000, x, 0f, it) }
            for (rate in listOf(44_100, 96_000)) {
                val gains = listOf(fc / 2, fc, fc * 2).map { gainDb(rate, x, 0f, it) }
                assertThat(gains[0]).isWithin(0.3).of(reference[0])
                assertThat(gains[1]).isWithin(0.05).of(reference[1])
                assertThat(gains[2]).isWithin(0.3).of(reference[2])
            }
        }
    }

    @Test
    fun `a jump of X glides without a step in the output`() {
        val rate = 48_000
        val input = Signals.sines(rate, rate, 0.5, 440.0)
        val inputCurvature = FilterProbe.maxSecondDifference(input, 0, input.size)
        val filter = morph(rate, x = 0f)
        val output = input.copyOf()
        val half = rate / 2
        filter.process(output, 0, half)
        filter.setPosition(-1f, 0f) // detent to the 200 Hz low-pass in one jump (no resonance: the gain stays <= 1)
        filter.process(output, half, rate - half)

        // A fast sweep bends the phase of the sine, which briefly raises its curvature (up to about 1.7 times here);
        // an unsmoothed jump steps the output by more than ten times the sine's own curvature.
        assertThat(FilterProbe.maxSecondDifference(output, half - 4, rate)).isAtMost(inputCurvature * 2 + 1e-3)
    }

    @Test
    fun `crossing from low-pass to high-pass glides without a step`() {
        val rate = 48_000
        val input = Signals.sines(rate, rate, 0.5, 440.0)
        val inputCurvature = FilterProbe.maxSecondDifference(input, 0, input.size)
        val filter = morph(rate, x = -0.7f)
        val output = input.copyOf()
        val half = rate / 2
        filter.process(output, 0, half)
        filter.setPosition(0.7f, 0f)
        filter.process(output, half, rate - half)

        assertThat(FilterProbe.maxSecondDifference(output, half - 4, rate)).isAtMost(inputCurvature * 2 + 1e-3)
    }

    @Test
    fun `random moves on loud noise never produce a non-finite or runaway sample`() {
        val random = Random(42)
        val filter = morph(48_000, channels = 2, x = 0f)
        val buffer = FloatArray(2 * 256)
        repeat(2000) {
            for (i in buffer.indices) buffer[i] = (random.nextFloat() * 2f - 1f)
            if (random.nextInt(4) == 0) filter.setPosition(random.nextFloat() * 2f - 1f, random.nextFloat())
            filter.process(buffer, 0, 256)
            for (v in buffer) {
                assertThat(v.isFinite()).isTrue()
                assertThat(abs(v)).isLessThan(32f)
            }
        }
    }

    @Test
    fun `non-finite input becomes silence and never escapes`() {
        val filter = morph(48_000, x = -0.5f, y = 1f)
        val buffer = FloatArray(480) { if (it % 3 == 0) Float.NaN else if (it % 3 == 1) Float.NEGATIVE_INFINITY else 0.2f }
        filter.process(buffer, 0, 480)
        for (v in buffer) assertThat(v.isFinite()).isTrue()
        val after = FloatArray(480) { 0.2f }
        filter.process(after, 0, 480)
        for (v in after) assertThat(v.isFinite()).isTrue()
    }

    @Test
    fun `processing a buffer allocates nothing`() {
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val filter = morph(48_000, channels = 2, x = -0.5f, y = 0.5f)
        val buffer = noise(2 * 480, seed = 9)
        var flip = false
        val step = {
            flip = !flip
            filter.setPosition(if (flip) -0.5f else 0.6f, 0.5f)
            filter.process(buffer, 0, 480)
        }
        repeat(5000) { step() } // warm up the JIT
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        repeat(500) { step() }
        assertThat(threads.getThreadAllocatedBytes(id) - before).isLessThan(4096L)
    }
}
