package io.sonora.core.djengine.mel

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.djengine.beats.BeatThisCase
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djmodel.MelSpectrogram
import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

class LogMelSinkTest {

    private fun run(samples: FloatArray, blockSize: Int = samples.size.coerceAtLeast(1)): MelSpectrogram {
        val sink = LogMelSink().create()
        var at = 0
        while (at < samples.size) {
            val n = minOf(blockSize, samples.size - at)
            sink.accept(samples, at, n)
            at += n
        }
        return sink.finish()
    }

    private fun frame(mel: MelSpectrogram, f: Int) = FloatArray(mel.binCount) { mel[f, it] }

    private fun argMax(v: FloatArray): Int = v.indices.maxByOrNull { v[it] }!!

    private fun noise(n: Int, seed: Long = 42L): FloatArray = Random(seed).let { r -> FloatArray(n) { (r.nextDouble() - 0.5).toFloat() } }

    // Slaney mel scale, written out again here so the expected band does not come from the code under test.
    private fun hzToMel(hz: Double) = if (hz < 1000.0) hz / (200.0 / 3) else 15.0 + ln(hz / 1000.0) / (ln(6.4) / 27.0)
    private fun melToHz(mel: Double) = if (mel < 15.0) mel * (200.0 / 3) else 1000.0 * exp((ln(6.4) / 27.0) * (mel - 15.0))

    private fun bandCentreHz(band: Int): Double {
        val low = hzToMel(30.0)
        val step = (hzToMel(11_000.0) - low) / 129
        return melToHz(low + (band + 1) * step)
    }

    /**
     * Straight transcription of the README with a direct DFT and an explicitly padded signal (numpy `reflect`, which
     * repeats the reflection when the signal is shorter than the padding), using the golden filterbank file.
     */
    private fun naiveLogMel(x: FloatArray): Array<FloatArray> {
        val fb = BeatThisCase.melFilterbank()
        val n = x.size
        fun reflect(i: Int): Int {
            if (n == 1) return 0
            val period = 2 * (n - 1)
            val m = Math.floorMod(i, period)
            return if (m < n) m else period - m
        }
        val padded = DoubleArray(n + 1024) { x[reflect(it - 512)].toDouble() }
        return Array(1 + n / 441) { f ->
            val windowed = DoubleArray(1024) { padded[f * 441 + it] * (0.5 - 0.5 * cos(2.0 * PI * it / 1024)) }
            val magnitude = DoubleArray(513) { k ->
                var re = 0.0
                var im = 0.0
                for (i in 0 until 1024) {
                    re += windowed[i] * cos(2.0 * PI * k * i / 1024)
                    im -= windowed[i] * sin(2.0 * PI * k * i / 1024)
                }
                sqrt(re * re + im * im) / sqrt(1024.0)
            }
            FloatArray(128) { band -> ln1p(1000.0 * (0 until 513).sumOf { magnitude[it] * fb[it * 128 + band] }).toFloat() }
        }
    }

    @Test
    fun `the sink wants 22050 Hz mono audio`() {
        assertThat(LogMelSink().sampleRateHz).isEqualTo(22_050)
    }

    @Test
    fun `a pure sine peaks in the mel band whose centre is nearest its frequency`() {
        for (hz in listOf(1_000.0, 2_000.0, 4_000.0, 8_000.0)) {
            val mel = run(SyntheticAudio.sine(hz, 1_500.0, 22_050))
            val step = (hzToMel(11_000.0) - hzToMel(30.0)) / 129
            val expected = ((hzToMel(hz) - hzToMel(30.0)) / step - 1).roundToInt()

            assertThat(mel.binCount).isEqualTo(128)
            for (f in 5 until mel.frameCount - 5) assertWithMessage("$hz Hz frame $f").that(argMax(frame(mel, f))).isEqualTo(expected)
        }
    }

    @Test
    fun `a sine on an FFT bin has the compressed magnitude the formula predicts`() {
        // A Hann-windowed sine of amplitude A exactly on bin 100 gives |X[100]| = A N / 4 and |X[99]| = |X[101]| = A N / 8
        // (nothing elsewhere); the magnitudes are then divided by sqrt(N) = 32 and weighted by the filterbank.
        val hz = 100 * 22_050.0 / 1_024
        val mel = run(SyntheticAudio.sine(hz, 1_500.0, 22_050, amplitude = 0.5))
        val fb = BeatThisCase.melFilterbank()
        val band = argMax(frame(mel, 10))
        val sum = fb[100 * 128 + band] * (0.5 * 1024 / 4) + (fb[99 * 128 + band] + fb[101 * 128 + band]) * (0.5 * 1024 / 8)
        val expected = ln1p(1000.0 * sum / 32.0)

        assertThat(abs(mel[10, band] - expected)).isLessThan(5e-3)
    }

    @Test
    fun `the frame count is one plus the samples divided by the hop, rounded down`() {
        for (n in listOf(1, 100, 440, 441, 442, 512, 513, 882, 22_050, 44_100 + 17)) {
            assertWithMessage("$n samples").that(run(noise(n)).frameCount).isEqualTo(1 + n / 441)
        }
    }

    @Test
    fun `the frame rate is 50 per second and there are 128 bins`() {
        val mel = run(noise(3_000))

        assertThat(mel.framesPerSecond).isEqualTo(50.0)
        assertThat(mel.binCount).isEqualTo(128)
    }

    @Test
    fun `no samples give no frames`() {
        assertThat(run(FloatArray(0)).frameCount).isEqualTo(0)
    }

    @Test
    fun `short and long signals match a direct transcription including the reflected start and end`() {
        for (n in listOf(1, 2, 100, 512, 513, 514, 700, 1_500, 3_000)) {
            val x = noise(n)
            val expected = naiveLogMel(x)
            val mel = run(x)

            assertThat(mel.frameCount).isEqualTo(expected.size)
            var worst = 0f
            for (f in expected.indices) for (b in 0 until 128) worst = maxOf(worst, abs(mel[f, b] - expected[f][b]))
            assertWithMessage("$n samples, worst difference").that(worst).isLessThan(1e-4f)
        }
    }

    @Test
    fun `silence gives an all zero log-mel`() {
        val mel = run(FloatArray(10_000))

        for (f in 0 until mel.frameCount) for (b in 0 until 128) assertThat(mel[f, b]).isEqualTo(0f)
    }

    @Test
    fun `the result does not depend on how the stream is cut into blocks`() {
        val long = noise(30_000, seed = 7L)
        val short = noise(400, seed = 9L)
        for (audio in listOf(long, short)) {
            val whole = run(audio)
            for (block in listOf(1, 7, 441, 512, 513, 1_000, 4_096)) {
                val mel = run(audio, block)
                assertThat(mel.frameCount).isEqualTo(whole.frameCount)
                for (f in 0 until whole.frameCount) {
                    assertWithMessage("block $block frame $f").that(frame(mel, f)).isEqualTo(frame(whole, f))
                }
            }
        }
    }

    @Test
    fun `a block can be a window into a larger array`() {
        val audio = noise(5_000)
        val padded = FloatArray(5_000 + 300) { if (it < 100) 9f else if (it >= 5_100) -9f else audio[it - 100] }
        val sink = LogMelSink().create()
        sink.accept(padded, 100, 2_500)
        sink.accept(padded, 2_600, 2_500)
        val mel = sink.finish()

        val expected = run(audio)
        for (f in 0 until expected.frameCount) assertThat(frame(mel, f)).isEqualTo(frame(expected, f))
    }

    @Test
    fun `golden synthetic audio matches the stored log-mel slices within 5e-4`() {
        for (name in GOLDEN_CASES) {
            val case = BeatThisCase.load(name)
            val mel = run(case.audio())

            assertWithMessage("$name frames").that(mel.frameCount).isEqualTo(case.frames)
            for (slice in case.melSlices) {
                var worst = 0f
                for (i in 0 until slice.count) for (b in 0 until 128) {
                    worst = maxOf(worst, abs(mel[slice.start + i, b] - slice.values[i * 128 + b]))
                }
                assertWithMessage("$name ${slice.name}, worst difference").that(worst).isLessThan(5e-4f)
            }
        }
    }

    @Test
    fun `the regenerated golden audio matches the generator parameters stored with the goldens`() {
        for (name in GOLDEN_CASES) {
            val case = BeatThisCase.load(name)
            val audio = case.audio()

            assertThat(audio.size).isEqualTo(case.numSamples)
            assertThat(case.clickSampleIndices()).isEqualTo(case.storedClickSampleIndices)
            for (i in case.probe.indices) {
                assertWithMessage("$name probe $i").that(abs(audio[case.probeStart + i] - case.probe[i])).isLessThan(1e-6f)
            }
            assertThat(abs(audio.maxOf { abs(it) } - case.peak)).isLessThan(1e-6)
        }
    }

    @Test
    fun `accepting samples allocates only the output`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val audio = noise(22_050 * 20)
        run(audio, 512)

        val sink = LogMelSink().create()
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        var at = 0
        while (at < audio.size) {
            val n = minOf(512, audio.size - at)
            sink.accept(audio, at, n)
            at += n
        }
        val allocated = threads.getThreadAllocatedBytes(id) - before

        // 20 s are 441,000 samples and 1,001 frames of 128 floats (about 513 KB of output); a per-sample allocation
        // would cost megabytes more.
        assertThat(allocated).isLessThan(700_000L)
        sink.finish()
    }

    private companion object {
        val GOLDEN_CASES = listOf("click_090", "click_128", "click_174", "ramp_120_124", "click_128_short", "silence")
    }
}
