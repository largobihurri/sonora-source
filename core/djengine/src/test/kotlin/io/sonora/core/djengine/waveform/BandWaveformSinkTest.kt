package io.sonora.core.djengine.waveform

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.fixtures.SyntheticAudio
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.domain.analysis.BlueWaveform
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Random
import kotlin.math.pow

class BandWaveformSinkTest {

    /** Feeds [audio] to a fresh sink in blocks of [blockSize], read from a padded array so the offset is exercised. */
    private fun run(
        audio: FloatArray,
        sampleRateHz: Int = 44_100,
        blockSize: Int = 4_096,
        sink: BandWaveformSink = BandWaveformSink(sampleRateHz),
    ): BandWaveform {
        val padding = 3
        val padded = FloatArray(audio.size + 2 * padding) { Float.NaN }
        audio.copyInto(padded, padding)
        val instance = sink.create()
        var at = 0
        while (at < audio.size) {
            val count = minOf(blockSize, audio.size - at)
            instance.accept(padded, padding + at, count)
            at += count
        }
        return instance.finish()
    }

    /** Mean of the steady-state columns of [plane], skipping the filters' start-up. */
    private fun steadyMean(plane: ByteArray, skip: Int = 30): Double =
        plane.drop(skip).map { (it.toInt() and 0xFF).toDouble() }.average()

    private fun db(ratio: Double) = 20 * kotlin.math.log10(ratio)

    private fun dbFactor(db: Double) = 10.0.pow(db / 20)

    private fun mixture(seconds: Double, rate: Int, seed: Long): FloatArray {
        val random = Random(seed)
        val tone = SyntheticAudio.sine(220.0, seconds * 1000, rate, 0.3)
        val tone2 = SyntheticAudio.sine(2_345.0, seconds * 1000, rate, 0.2)
        val tone3 = SyntheticAudio.sine(9_000.0, seconds * 1000, rate, 0.1)
        return FloatArray(tone.size) { tone[it] + tone2[it] + tone3[it] + (random.nextFloat() - 0.5f) * 0.2f }
    }

    @Test
    fun `a 100 Hz sine lands in the low band`() {
        val waveform = run(SyntheticAudio.sine(100.0, 2_000.0, amplitude = 0.8))

        val low = steadyMean(waveform.low())
        val mid = steadyMean(waveform.mid())
        val high = steadyMean(waveform.high())
        assertThat(low).isGreaterThan(190.0) // 0.8 * 255 = 204, less the filter's small loss
        assertThat(mid).isAtMost(low * dbFactor(-30.0))
        assertThat(high).isAtMost(low * dbFactor(-30.0))
        assertThat(steadyMean(waveform.peak())).isWithin(2.0).of(0.8 * 255)
    }

    @Test
    fun `a 1 kHz sine lands in the mid band`() {
        val waveform = run(SyntheticAudio.sine(1_000.0, 2_000.0, amplitude = 0.8))

        val low = steadyMean(waveform.low())
        val mid = steadyMean(waveform.mid())
        val high = steadyMean(waveform.high())
        assertThat(mid).isGreaterThan(150.0)
        assertThat(low).isAtMost(mid * dbFactor(-6.0)) // Bessel skirts are gentle: 1 kHz is under an octave from 600 Hz
        assertThat(high).isAtMost(mid * dbFactor(-20.0))
    }

    @Test
    fun `an 8 kHz sine lands in the high band`() {
        val waveform = run(SyntheticAudio.sine(8_000.0, 2_000.0, amplitude = 0.8))

        val low = steadyMean(waveform.low())
        val mid = steadyMean(waveform.mid())
        val high = steadyMean(waveform.high())
        assertThat(high).isGreaterThan(150.0)
        assertThat(mid).isAtMost(high * dbFactor(-10.0))
        assertThat(low).isAtMost(high * dbFactor(-40.0))
    }

    @Test
    fun `the level is linear in the amplitude, minus 6 dB halves it`() {
        for (frequency in doubleArrayOf(100.0, 1_000.0, 8_000.0)) {
            val loud = run(SyntheticAudio.sine(frequency, 1_500.0, amplitude = 0.8))
            val quiet = run(SyntheticAudio.sine(frequency, 1_500.0, amplitude = 0.8 * dbFactor(-6.0206)))

            for ((a, b) in listOf(
                loud.low() to quiet.low(),
                loud.mid() to quiet.mid(),
                loud.high() to quiet.high(),
                loud.peak() to quiet.peak(),
            )) {
                val ratio = steadyMean(b) / steadyMean(a)
                // The planes that hold the tone: the level halves (within 1%, rounding and the filter's float input).
                // The planes that do not are a few counts; skip them.
                if (steadyMean(a) > 40.0) assertThat(db(ratio)).isWithin(0.1).of(-6.0206)
            }
        }
    }

    @Test
    fun `silence gives zero columns`() {
        val waveform = run(FloatArray(44_100 * 2))

        assertThat(waveform.columnCount).isEqualTo(300)
        assertThat(waveform.low()).isEqualTo(ByteArray(300))
        assertThat(waveform.mid()).isEqualTo(ByteArray(300))
        assertThat(waveform.high()).isEqualTo(ByteArray(300))
        assertThat(waveform.peak()).isEqualTo(ByteArray(300))
    }

    @Test
    fun `a sound followed by silence decays to exactly zero`() {
        val burst = SyntheticAudio.sine(500.0, 300.0, amplitude = 0.9)
        val audio = burst + FloatArray(44_100 * 3)

        val waveform = run(audio)

        // The filters ring for a few ms; well before the end every plane is zero, with no residual from the state.
        val tail = waveform.columnCount - 20
        for (plane in listOf(waveform.low(), waveform.mid(), waveform.high(), waveform.peak())) {
            assertThat(plane.drop(tail).all { it.toInt() == 0 }).isTrue()
        }
        assertThat(steadyMean(waveform.peak(), skip = 0)).isGreaterThan(0.0)
    }

    @Test
    fun `non-finite samples count as silence`() {
        val audio = FloatArray(44_100) { if (it % 3 == 0) Float.NaN else Float.POSITIVE_INFINITY }

        val waveform = run(audio)

        assertThat(waveform.columnCount).isEqualTo(150)
        assertThat(waveform.peak()).isEqualTo(ByteArray(150))
        assertThat(waveform.low()).isEqualTo(ByteArray(150))
    }

    @Test
    fun `the output is the same for blocks of 1, 7 and 4096 samples`() {
        val audio = mixture(1.2, 44_100, seed = 7L)

        val reference = run(audio, blockSize = 4_096)

        assertThat(reference.columnCount).isEqualTo(BlueWaveform.detailColumnCount(audio.size.toLong(), 44_100))
        assertThat(run(audio, blockSize = 1)).isEqualTo(reference)
        assertThat(run(audio, blockSize = 7)).isEqualTo(reference)
        assertThat(run(audio, blockSize = 1_000_003)).isEqualTo(reference)
        // A column boundary (294 samples) in the middle of a block, and an empty call, change nothing.
        val instance = BandWaveformSink().create()
        instance.accept(audio, 0, 0)
        instance.accept(audio, 0, 300)
        instance.accept(audio, 300, 0)
        instance.accept(audio, 300, audio.size - 300)
        assertThat(instance.finish()).isEqualTo(reference)
    }

    @Test
    fun `the block-size invariance holds at 48 kHz and 22 kHz too`() {
        for (rate in intArrayOf(48_000, 22_050)) {
            val audio = mixture(0.9, rate, seed = 3L)

            assertThat(run(audio, rate, blockSize = 13)).isEqualTo(run(audio, rate, blockSize = 4_096))
        }
    }

    @Test
    fun `the column count follows the reference column math`() {
        for (rate in intArrayOf(44_100, 48_000, 22_050, 32_000, 96_000)) {
            val frames = longArrayOf(
                0, 1, 146, 147, 148, 293, 294, 295, rate.toLong(), rate * 3L + 17, rate * 10L - 1, rate * 10L,
            )
            for (total in frames) {
                val waveform = run(FloatArray(total.toInt()) { 0.25f }, rate)

                assertThat(waveform.columnCount).isEqualTo(BlueWaveform.detailColumnCount(total, rate))
            }
        }
        assertThat(run(FloatArray(44_100 * 3), 44_100).columnCount).isEqualTo(450)
        assertThat(run(FloatArray(48_000 * 3), 48_000).columnCount).isEqualTo(450)
        assertThat(run(FloatArray(22_050 * 3), 22_050).columnCount).isEqualTo(450)
    }

    @Test
    fun `columns start on the same frames as the blue waveform`() {
        for (rate in intArrayOf(44_100, 48_000, 22_050, 32_000)) {
            for (column in longArrayOf(1, 2, 7, 100, 149)) {
                val start = BlueWaveform.detailColumnStartFrame(column, rate).toInt()
                val length = BlueWaveform.detailColumnStartFrame(column + 3, rate).toInt()

                // An impulse on the first frame of a column belongs to that column; one frame earlier, to the previous.
                val onStart = FloatArray(length).also { it[start] = 1f }
                val before = FloatArray(length).also { it[start - 1] = 1f }
                val peakOnStart = run(onStart, rate).peak()
                val peakBefore = run(before, rate).peak()

                assertThat(peakOnStart.indices.filter { peakOnStart[it].toInt() != 0 }).containsExactly(column.toInt())
                assertThat(peakBefore.indices.filter { peakBefore[it].toInt() != 0 }).containsExactly(column.toInt() - 1)
                assertThat(peakOnStart[column.toInt()].toInt() and 0xFF).isEqualTo(255)
            }
        }
    }

    @Test
    fun `a partial last column is kept and measured over the samples it has`() {
        val rate = 44_100
        val total = 294 * 3 + 50
        val audio = FloatArray(total).also { it[total - 1] = 0.5f }

        val waveform = run(audio, rate)

        assertThat(waveform.columnCount).isEqualTo(4)
        assertThat(waveform.peak()[3].toInt() and 0xFF).isEqualTo(128)
        assertThat(waveform.peak()[2].toInt()).isEqualTo(0)
    }

    @Test
    fun `no audio gives an empty waveform`() {
        val waveform = BandWaveformSink().create().finish()

        assertThat(waveform.columnCount).isEqualTo(0)
        assertThat(waveform.version).isEqualTo(BandWaveformSink.ALGORITHM_VERSION)
    }

    @Test
    fun `the sink reports its rate and version`() {
        assertThat(BandWaveformSink().sampleRateHz).isEqualTo(44_100)
        assertThat(BandWaveformSink(48_000).sampleRateHz).isEqualTo(48_000)
        assertThat(BandWaveformSink.LOW_CROSSOVER_HZ).isEqualTo(600.0)
        assertThat(BandWaveformSink.HIGH_CROSSOVER_HZ).isEqualTo(4_000.0)
        assertThat(BandWaveformSink.ALGORITHM_VERSION).isAtLeast(1)

        val custom = run(SyntheticAudio.sine(1_000.0, 500.0), sink = BandWaveformSink(version = 9))
        assertThat(custom.version).isEqualTo(9)
    }

    @Test
    fun `the curve is applied to every plane`() {
        val audio = SyntheticAudio.sine(1_000.0, 1_000.0, amplitude = 0.4)

        val linear = run(audio)
        val ceiling = run(audio, sink = BandWaveformSink(curve = BandLevelCurve(ceiling = 0.4)))

        assertThat(steadyMean(ceiling.peak())).isWithin(0.5).of(255.0)
        assertThat(steadyMean(linear.peak())).isWithin(1.0).of(0.4 * 255)
        assertThat(steadyMean(ceiling.mid())).isGreaterThan(steadyMean(linear.mid()) * 2)
    }

    @Test
    fun `a tone above the ceiling saturates at 255`() {
        val waveform = run(SyntheticAudio.sine(100.0, 1_000.0, amplitude = 1.0))

        assertThat(waveform.peak().drop(30).all { (it.toInt() and 0xFF) >= 254 }).isTrue()
        assertThat(waveform.low().drop(30).all { (it.toInt() and 0xFF) <= 255 }).isTrue()
    }

    @Test
    fun `using the sink after finish or with a bad range is rejected`() {
        val instance = BandWaveformSink().create()
        instance.accept(FloatArray(10), 0, 10)
        instance.finish()

        assertThrows(IllegalStateException::class.java) { instance.accept(FloatArray(10), 0, 10) }
        assertThrows(IllegalStateException::class.java) { instance.finish() }
        assertThrows(IllegalArgumentException::class.java) { BandWaveformSink().create().accept(FloatArray(10), 5, 6) }
        assertThrows(IllegalArgumentException::class.java) { BandWaveformSink().create().accept(FloatArray(10), -1, 2) }
        assertThrows(IllegalArgumentException::class.java) { BandWaveformSink(sampleRateHz = 8_000) }
        assertThrows(IllegalArgumentException::class.java) { BandWaveformSink(sampleRateHz = 400_000) }
    }

    @Test
    fun `each sink has its own filter state`() {
        val factory = BandWaveformSink()
        val audio = SyntheticAudio.sine(300.0, 500.0, amplitude = 0.7)

        val a = factory.create().also { it.accept(audio, 0, audio.size) }
        val b = factory.create().also { it.accept(audio, 0, audio.size) }

        assertThat(a.finish()).isEqualTo(b.finish())
    }
}
