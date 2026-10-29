package io.sonora.core.djengine.key

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.domain.analysis.MusicalKey
import java.lang.management.ManagementFactory
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Test

/**
 * Bin convention under test: bin `3 * pc` is pitch class `pc` (0 = C .. 11 = B, as [MusicalKey] and
 * [SyntheticAudio.pitchClassHz] number them) at reference tuning, so A is bin 27 and each bin is 1/3 semitone.
 */
class HpcpSinkTest {

    private fun run(
        samples: FloatArray,
        sampleRateHz: Int = 44_100,
        blockSize: Int = samples.size.coerceAtLeast(1),
    ): ChromaFrames {
        val sink = HpcpSink(sampleRateHz).create()
        var at = 0
        while (at < samples.size) {
            val n = minOf(blockSize, samples.size - at)
            sink.accept(samples, at, n)
            at += n
        }
        return sink.finish()
    }

    private fun frame(frames: ChromaFrames, f: Int) = FloatArray(ChromaFrames.BINS_PER_FRAME) { frames[f, it] }

    private fun argMax(v: FloatArray): Int = v.indices.maxByOrNull { v[it] }!!

    /** Energy of pitch class [pc]: its centre bin and the two neighbours (the cos^2 window spreads over them). */
    private fun pitchClassEnergy(v: FloatArray, pc: Int): Float =
        (-1..1).sumOf { v[Math.floorMod(3 * pc + it, 36)].toDouble() }.toFloat()

    private fun topPitchClasses(v: FloatArray, count: Int): Set<Int> =
        (0 until 12).sortedByDescending { pitchClassEnergy(v, it) }.take(count).toSet()

    private fun hz(pitchClass: Int, octave: Int = 1) = SyntheticAudio.pitchClassHz(pitchClass, octave)

    @Test
    fun `an A440 sine peaks at the A bin in every frame`() {
        val frames = run(SyntheticAudio.sine(440.0, 2_000.0))

        assertThat(frames.frameCount).isGreaterThan(5)
        for (f in 0 until frames.frameCount) assertThat(argMax(frame(frames, f))).isEqualTo(27)
    }

    @Test
    fun `bin zero is C and each pitch class sits at three times its number`() {
        for (pc in 0 until 12) {
            val frames = run(SyntheticAudio.sine(hz(pc), 1_000.0))
            assertThat(argMax(frame(frames, 2))).isEqualTo(3 * pc)
        }
    }

    @Test
    fun `a sine a third of a semitone sharp moves the peak up one bin`() {
        val sharp = run(SyntheticAudio.sine(440.0 * 2.0.pow(1.0 / 36), 1_000.0))
        val flat = run(SyntheticAudio.sine(440.0 * 2.0.pow(-1.0 / 36), 1_000.0))

        assertThat(argMax(frame(sharp, 3))).isEqualTo(28)
        assertThat(argMax(frame(flat, 3))).isEqualTo(26)
    }

    @Test
    fun `a C major triad puts its energy on C E and G`() {
        val frames = run(SyntheticAudio.triad(MusicalKey.C_MAJOR, 2_000.0))

        for (f in 0 until frames.frameCount) {
            assertThat(topPitchClasses(frame(frames, f), 3)).containsExactly(0, 4, 7)
        }
    }

    @Test
    fun `every major and minor triad is recognised by its three pitch classes`() {
        for (key in MusicalKey.entries) {
            val root = (key.index - 1) % 12
            val third = root + if (key.isMinor) 3 else 4
            val expected = setOf(root, third % 12, (root + 7) % 12)

            val frames = run(SyntheticAudio.triad(key, 1_000.0))

            assertWithMessage(key.name).that(topPitchClasses(frame(frames, 2), 3)).isEqualTo(expected)
        }
    }

    @Test
    fun `a harmonic-rich A tone still peaks at A and not at its third harmonic E`() {
        val rate = 44_100
        val tone = FloatArray(2 * rate) { n ->
            var v = 0.0
            for (h in 1..6) v += sin(2 * PI * 440.0 * h * n / rate) / h
            (0.2 * v).toFloat()
        }

        val v = frame(run(tone), 3)

        assertThat(argMax(v)).isEqualTo(27)
        assertThat(pitchClassEnergy(v, 9)).isGreaterThan(1.5f * pitchClassEnergy(v, 4))
    }

    @Test
    fun `a weak partial is lifted by the whitening`() {
        val rate = 44_100
        val loud = SyntheticAudio.sine(hz(0, 2), 1_000.0, rate, amplitude = 0.8)
        val weak = SyntheticAudio.sine(hz(7, 2), 1_000.0, rate, amplitude = 0.01)
        val mix = FloatArray(loud.size) { loud[it] + weak[it] }

        val v = frame(run(mix), 3)

        assertThat(argMax(v)).isEqualTo(0)
        // The weak partial is 38 dB down; without whitening its squared amplitude would be about 6e-6 of the loud one.
        assertThat(v[21]).isGreaterThan(0.01f)
    }

    @Test
    fun `the result does not depend on how the samples are cut into blocks`() {
        val audio = SyntheticAudio.diatonicProgression(MusicalKey.G_MAJOR, 700.0)
        val whole = run(audio)

        for (block in intArrayOf(1, 7, 1_000, 4_096, 5_000)) {
            val chunked = run(audio, blockSize = block)
            assertThat(chunked.frameCount).isEqualTo(whole.frameCount)
            for (f in 0 until whole.frameCount) {
                for (b in 0 until ChromaFrames.BINS_PER_FRAME) {
                    assertThat(chunked[f, b]).isEqualTo(whole[f, b])
                }
            }
        }
    }

    @Test
    fun `frame size follows the sample rate and the frame count drops a partial last frame`() {
        assertThat(HpcpSink.frameSizeFor(44_100)).isEqualTo(4_096)
        assertThat(HpcpSink.frameSizeFor(48_000)).isEqualTo(4_096)
        assertThat(HpcpSink.frameSizeFor(32_000)).isEqualTo(4_096)
        assertThat(HpcpSink.frameSizeFor(22_050)).isEqualTo(2_048)
        assertThat(HpcpSink.frameSizeFor(16_000)).isEqualTo(2_048)
        assertThat(HpcpSink.frameSizeFor(96_000)).isEqualTo(8_192)

        // (sample rate, samples, expected frames): samples / frameSize rounded down.
        val cases = listOf(
            Triple(44_100, 441_000, 107), // 10 s, 107.7 frames
            Triple(44_100, 44_100, 10), // 1 s, 10.8
            Triple(22_050, 220_500, 107), // 10 s, 107.7
            Triple(48_000, 480_000, 117), // 10 s, 117.2
            Triple(44_100, 4_096 * 3, 3),
            Triple(44_100, 4_096 * 3 + 4_095, 3), // a partial last frame is dropped
            Triple(44_100, 4_096, 1),
            Triple(44_100, 4_095, 0),
            Triple(44_100, 0, 0),
        )
        for ((rate, count, expected) in cases) {
            val frames = run(sineOf(count, rate), rate)
            assertWithMessage("$rate Hz, $count samples").that(frames.frameCount).isEqualTo(expected)
            assertThat(frames.framesPerSecond).isWithin(1e-9).of(rate.toDouble() / HpcpSink.frameSizeFor(rate))
        }
    }

    private fun sineOf(count: Int, rate: Int) = FloatArray(count) { (0.5 * sin(2 * PI * 440.0 * it / rate)).toFloat() }

    @Test
    fun `A440 is the A bin at other sample rates too`() {
        for (rate in intArrayOf(16_000, 22_050, 32_000, 48_000, 96_000)) {
            val frames = run(SyntheticAudio.sine(440.0, 2_000.0, rate), rate)
            assertThat(frames.frameCount).isGreaterThan(2)
            assertWithMessage("$rate Hz").that(argMax(frame(frames, 1))).isEqualTo(27)
        }
    }

    @Test
    fun `reference tuning moves the bins`() {
        val sink = HpcpSink(44_100, referenceFrequencyHz = 432.0).create()
        val tone = SyntheticAudio.sine(432.0, 1_000.0)
        sink.accept(tone, 0, tone.size)

        assertThat(argMax(frame(sink.finish(), 3))).isEqualTo(27)
    }

    @Test
    fun `silence gives zeros and never NaN`() {
        val frames = run(FloatArray(44_100))

        assertThat(frames.frameCount).isEqualTo(10)
        for (f in 0 until frames.frameCount) for (b in 0 until 36) assertThat(frames[f, b]).isEqualTo(0f)
    }

    @Test
    fun `a constant offset gives no chroma`() {
        val frames = run(FloatArray(44_100) { 0.5f })

        for (f in 0 until frames.frameCount) for (b in 0 until 36) assertThat(frames[f, b]).isEqualTo(0f)
    }

    @Test
    fun `every sounding frame is normalised to a unit maximum and values stay finite`() {
        val audio = SyntheticAudio.noiseBursts(doubleArrayOf(0.0, 1_000.0), burstMs = 400.0, durationMs = 2_000.0)

        val frames = run(audio)

        var sounding = 0
        for (f in 0 until frames.frameCount) {
            val v = frame(frames, f)
            assertThat(v.all { it.isFinite() && it >= 0f && it <= 1f }).isTrue()
            if (v.any { it > 0f }) {
                sounding++
                assertThat(v.max()).isEqualTo(1f)
            }
        }
        assertThat(sounding).isGreaterThan(0)
        assertThat(sounding).isLessThan(frames.frameCount)
    }

    @Test
    fun `a silent frame between two tones stays zero while the tones keep their bin`() {
        val tone = SyntheticAudio.sine(440.0, 1_000.0)
        val audio = tone + FloatArray(44_100) + tone

        val frames = run(audio)

        assertThat(argMax(frame(frames, 2))).isEqualTo(27)
        val middle = frames.frameCount / 2
        assertThat(frame(frames, middle).all { it == 0f }).isTrue()
        assertThat(argMax(frame(frames, frames.frameCount - 3))).isEqualTo(27)
    }

    @Test
    fun `accepting samples allocates only the growing output`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val audio = SyntheticAudio.diatonicProgression(MusicalKey.A_MINOR, 2_500.0)
        val warm = HpcpSink().create()
        warm.accept(audio, 0, audio.size)
        warm.finish()

        val sink = HpcpSink().create()
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        var at = 0
        while (at < audio.size) {
            val n = minOf(512, audio.size - at)
            sink.accept(audio, at, n)
            at += n
        }
        val allocated = threads.getThreadAllocatedBytes(id) - before

        // 10 s of audio is 441,000 samples; a per-sample allocation would cost megabytes. 107 frames of output are ~15 KB, plus growth copies.
        assertThat(allocated).isLessThan(64_000L)
        sink.finish()
    }
}
