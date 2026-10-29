package io.sonora.core.playback.equalizer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.equalizer.EqualizerLayout
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import org.junit.Test

/** The equalizer's DSP on generated PCM, in every supported encoding. */
@OptIn(UnstableApi::class)
class EqualizerAudioProcessorTest {

    private var settings = EqualizerSettings()
    private val processor = EqualizerAudioProcessor { settings }

    private fun configure(rate: Int, channels: Int, encoding: Int, floatOutput: Boolean = true): AudioProcessor.AudioFormat {
        processor.floatOutput = floatOutput
        val out = processor.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        processor.flush()
        return out
    }

    /** Runs [input] (interleaved, in [-1, 1]) through the processor in buffers of [chunkFrames] and decodes the output. */
    private fun run(input: FloatArray, channels: Int, inEncoding: Int, outEncoding: Int, chunkFrames: Int = 512): FloatArray {
        val out = FloatArray(input.size)
        var written = 0
        var frame = 0
        val frames = input.size / channels
        while (frame < frames) {
            val n = minOf(chunkFrames, frames - frame)
            processor.queueInput(Pcm.encode(input, frame * channels, n * channels, inEncoding))
            val output = processor.output
            written += Pcm.decode(output, outEncoding, out, written)
            frame += n
        }
        assertThat(written).isEqualTo(input.size)
        return out
    }

    private fun sine(frequency: Double, rate: Int, seconds: Double, amplitude: Double, channels: Int = 1): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * channels) { i -> (amplitude * sin(2 * PI * frequency * (i / channels) / rate)).toFloat() }
    }

    /** Peak absolute value of channel [channel] over the last [fraction] of [samples]. */
    private fun tailPeak(samples: FloatArray, channels: Int = 1, channel: Int = 0, fraction: Double = 0.25): Double {
        val frames = samples.size / channels
        var peak = 0.0
        for (f in (frames * (1 - fraction)).toInt() until frames) peak = max(peak, abs(samples[f * channels + channel].toDouble()))
        return peak
    }

    /** A sine's amplitude on channel [channel] from the RMS of the last [fraction], whatever the sampling phase. */
    private fun tailAmplitude(samples: FloatArray, channels: Int = 1, channel: Int = 0, fraction: Double = 0.25): Double {
        val frames = samples.size / channels
        val from = (frames * (1 - fraction)).toInt()
        var sum = 0.0
        for (f in from until frames) sum += samples[f * channels + channel].toDouble().let { it * it }
        return kotlin.math.sqrt(2 * sum / (frames - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    private fun eleven(band: Int, gain: Float) =
        EqualizerSettings(enabled = true, mode = EqualizerMode.ELEVEN_BANDS).withGain(EqualizerMode.ELEVEN_BANDS, band, gain)

    // Bypass and transparency

    @Test
    fun `switched off, 16-bit audio becomes float without changing a single value`() {
        configure(44_100, 2, C.ENCODING_PCM_16BIT)
        val input = FloatArray(4_000) { ((it * 7_919) % 65_536 - 32_768) / 32_768f }

        val output = run(input, 2, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)

        assertThat(output).isEqualTo(input)
    }

    @Test
    fun `switched off or flat, the stream's own encoding passes byte for byte`() {
        for (enabled in listOf(false, true)) {
            settings = EqualizerSettings(enabled = enabled)
            for (encoding in Pcm.ENCODINGS) {
                configure(48_000, 2, encoding, floatOutput = false)
                val bytes = Pcm.encode(sine(440.0, 48_000, 0.05, 0.9, 2), 0, 4_800, encoding)
                val expected = bytes.duplicate()

                processor.queueInput(bytes)

                assertThat(processor.output).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `switched off, it lets the sink take the stream's own buffers`() {
        configure(48_000, 2, C.ENCODING_PCM_FLOAT)
        assertThat(processor.isPassthrough()).isTrue()

        settings = eleven(5, 3f)
        assertThat(processor.isPassthrough()).isFalse()
    }

    @Test
    fun `16-bit audio switched off becomes float only when float output was asked for`() {
        assertThat(configure(44_100, 2, C.ENCODING_PCM_16BIT).encoding).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(configure(44_100, 2, C.ENCODING_PCM_16BIT, floatOutput = false).encoding).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(processor.isPassthrough()).isTrue()
    }

    // Filter response

    @Test
    fun `a cut band attenuates a sine at its centre by its gain`() {
        val bands = EqualizerLayout.bands(EqualizerMode.ELEVEN_BANDS)
        for (band in listOf(3, 6, 9)) {
            settings = eleven(band, -6f)
            configure(48_000, 1, C.ENCODING_PCM_FLOAT)

            val output = run(sine(bands[band].frequencyHz, 48_000, 0.5, 0.5), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

            assertThat(db(tailAmplitude(output) / 0.5)).isWithin(0.1).of(-6.0)
        }
    }

    @Test
    fun `a boost never takes a full-scale sine above 0 dBFS and lowers the rest by the same preamp`() {
        val bands = EqualizerLayout.bands(EqualizerMode.TWENTY_BANDS)
        settings = EqualizerSettings(enabled = true, mode = EqualizerMode.TWENTY_BANDS)
            .withGain(EqualizerMode.TWENTY_BANDS, 10, 12f)
            .withGain(EqualizerMode.TWENTY_BANDS, 11, 12f)
        val between = kotlin.math.sqrt(bands[10].frequencyHz * bands[11].frequencyHz)

        for (frequency in listOf(bands[10].frequencyHz, between, bands[11].frequencyHz)) {
            configure(44_100, 1, C.ENCODING_PCM_FLOAT)
            val output = run(sine(frequency, 44_100, 0.5, 1.0), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)
            assertThat(tailPeak(output)).isAtMost(1.0 + 1e-4)
        }

        configure(44_100, 1, C.ENCODING_PCM_FLOAT)
        val far = run(sine(100.0, 44_100, 0.5, 1.0), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)
        assertThat(db(tailPeak(far))).isLessThan(-12.0)
    }

    @Test
    fun `every channel is filtered on its own at any rate`() {
        settings = eleven(6, -12f)
        configure(96_000, 6, C.ENCODING_PCM_FLOAT)
        val input = FloatArray(96_000 / 2 * 6)
        val tone = sine(1_000.0, 96_000, 0.5, 0.5)
        for (f in tone.indices) input[f * 6 + 3] = tone[f]

        val output = run(input, 6, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

        assertThat(db(tailAmplitude(output, 6, 3) / 0.5)).isWithin(0.1).of(-12.0)
        for (channel in listOf(0, 1, 2, 4, 5)) assertThat(tailPeak(output, 6, channel, 1.0)).isEqualTo(0.0)
    }

    // Encodings

    @Test
    fun `16-bit and 24-bit big-endian audio are filtered into float`() {
        settings = eleven(6, -6f)
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN)) {
            configure(44_100, 2, encoding)
            val output = run(sine(1_000.0, 44_100, 0.5, 0.5, 2), 2, encoding, C.ENCODING_PCM_FLOAT)

            assertThat(db(tailAmplitude(output, 2, 1) / 0.5)).isWithin(0.1).of(-6.0)
        }
    }

    @Test
    fun `16-bit output keeps 16-bit and stays within a bit of the float result`() {
        settings = eleven(6, -6f)
        configure(44_100, 1, C.ENCODING_PCM_16BIT)
        // The first run also ramps the curve in; both runs below start from the finished curve and a clear history.
        run(sine(1_000.0, 44_100, 0.1, 0.5), 1, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        configure(44_100, 1, C.ENCODING_PCM_16BIT, floatOutput = false)
        val output = run(sine(1_000.0, 44_100, 0.2, 0.5), 1, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT)
        configure(44_100, 1, C.ENCODING_PCM_16BIT)
        val reference = run(sine(1_000.0, 44_100, 0.2, 0.5), 1, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)

        // Rounding (half a step) plus triangular dither (up to one step).
        for (i in output.indices) assertThat(abs(output[i] - reference[i]).toDouble()).isAtMost(1.6 / 32_768)
    }

    @Test
    fun `an unsupported encoding is refused`() {
        val refused = runCatching { configure(48_000, 2, C.ENCODING_AC3) }.exceptionOrNull()

        assertThat(refused).isInstanceOf(AudioProcessor.UnhandledAudioFormatException::class.java)
    }

    // Live changes and ramps

    @Test
    fun `a change ramps over about 30 ms instead of stepping`() {
        configure(48_000, 1, C.ENCODING_PCM_FLOAT)
        val dc = FloatArray(48_000 / 10) { 0.5f }
        run(dc, 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)
        // A +12 dB treble shelf brings a 12 dB preamp, which DC (no treble) shows on its own.
        settings = EqualizerSettings(enabled = true).withGain(EqualizerMode.FOUR_BANDS, 3, 12f)

        val output = run(dc, 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT, chunkFrames = 256)

        assertThat(output[0].toDouble()).isWithin(0.01).of(0.5)
        for (i in 1 until output.size) assertThat(abs(output[i] - output[i - 1]).toDouble()).isLessThan(0.002)
        assertThat(output[(0.015 * 48_000).toInt()].toDouble()).isGreaterThan(0.2)
        assertThat(output[(0.060 * 48_000).toInt()].toDouble()).isWithin(0.005).of(0.5 / 3.98)
    }

    @Test
    fun `switching off fades the curve out and then passes the stream untouched`() {
        settings = eleven(6, -12f)
        configure(48_000, 1, C.ENCODING_PCM_FLOAT)
        run(sine(1_000.0, 48_000, 0.2, 0.5), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)
        settings = settings.withEnabled(false)

        val fading = run(sine(1_000.0, 48_000, 0.01, 0.5), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)
        run(sine(1_000.0, 48_000, 0.1, 0.5), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

        assertThat(tailPeak(fading, fraction = 1.0)).isLessThan(0.45)
        assertThat(processor.isPassthrough()).isTrue()
    }

    @Test
    fun `switching modes moves to the other mode's curve`() {
        settings = eleven(6, -12f).withGain(EqualizerMode.FOUR_BANDS, 2, -3f)
        configure(48_000, 1, C.ENCODING_PCM_FLOAT)
        run(sine(1_000.0, 48_000, 0.2, 0.5), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

        settings = settings.withMode(EqualizerMode.FOUR_BANDS)
        val output = run(sine(1_000.0, 48_000, 0.4, 0.5), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

        assertThat(db(tailAmplitude(output) / 0.5)).isWithin(0.1).of(-3.0)
    }

    // Without the automatic preamp: the limiter

    private fun treble(gain: Float, automaticPreamp: Boolean) =
        EqualizerSettings(enabled = true, automaticPreamp = automaticPreamp).withGain(EqualizerMode.FOUR_BANDS, 3, gain)

    /** The largest step between neighbouring samples: a click shows up as one far above the tone's own. */
    private fun largestStep(samples: FloatArray): Double =
        (1 until samples.size).maxOf { abs(samples[it] - samples[it - 1]).toDouble() }

    @Test
    fun `without the automatic preamp a boost keeps the level and the limiter holds the peaks`() {
        settings = treble(12f, automaticPreamp = false)
        configure(48_000, 1, C.ENCODING_PCM_FLOAT)

        val bass = run(sine(100.0, 48_000, 0.5, 0.5), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)
        val boosted = run(sine(15_000.0, 48_000, 0.5, 1.0), 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

        assertThat(db(tailAmplitude(bass) / 0.5)).isWithin(0.2).of(0.0)
        for (sample in boosted) assertThat(abs(sample.toDouble())).isAtMost(PeakLimiter.CEILING + 1e-6)
        assertThat(tailPeak(boosted)).isGreaterThan(PeakLimiter.CEILING - 0.01)
    }

    @Test
    fun `turning the automatic preamp off and on again crossfades without a click`() {
        settings = treble(6f, automaticPreamp = true)
        configure(48_000, 1, C.ENCODING_PCM_FLOAT)
        val tone = sine(100.0, 48_000, 0.3, 0.4)
        val before = run(tone, 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

        settings = treble(6f, automaticPreamp = false)
        val off = run(tone, 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT, chunkFrames = 256)
        settings = treble(6f, automaticPreamp = true)
        val on = run(tone, 1, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT, chunkFrames = 256)

        assertThat(db(tailAmplitude(before) / 0.4)).isWithin(0.2).of(-6.0)
        assertThat(db(tailAmplitude(off) / 0.4)).isWithin(0.2).of(0.0)
        assertThat(db(tailAmplitude(on) / 0.4)).isWithin(0.2).of(-6.0)
        for (output in listOf(before + off, off + on)) assertThat(largestStep(output)).isLessThan(0.008)
    }

    @Test
    fun `switching the equalizer off with the limiter on still ends in the untouched stream`() {
        settings = treble(12f, automaticPreamp = false)
        configure(48_000, 2, C.ENCODING_PCM_FLOAT)
        run(sine(1_000.0, 48_000, 0.2, 0.5, 2), 2, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)
        settings = settings.withEnabled(false)

        val fading = run(sine(1_000.0, 48_000, 0.2, 0.5, 2), 2, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT, chunkFrames = 256)
        assertThat(processor.isPassthrough()).isTrue()
        val input = sine(440.0, 48_000, 0.05, 0.9, 2)
        val after = run(input, 2, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT)

        assertThat(largestStep(fading)).isLessThan(0.15)
        assertThat(after).isEqualTo(input)
    }

    @Test
    fun `the limiter allocates nothing either, switched in or out`() {
        settings = EqualizerSettings(enabled = true, mode = EqualizerMode.TWENTY_BANDS, automaticPreamp = false)
            .withGain(EqualizerMode.TWENTY_BANDS, 4, 6f)
        configure(48_000, 2, C.ENCODING_PCM_16BIT)
        val buffers = List(60) { Pcm.encode(sine(440.0, 48_000, 0.02, 0.9, 2), 0, 1_920, C.ENCODING_PCM_16BIT) }
        repeat(20) { processor.queueInput(buffers[it].duplicate()); processor.output }
        val automatic = EqualizerSettings(enabled = true, mode = EqualizerMode.TWENTY_BANDS).withGain(EqualizerMode.TWENTY_BANDS, 4, 6f)
        val limited = settings
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        val measured = buffers.subList(20, buffers.size).toTypedArray()
        val before = threads.getThreadAllocatedBytes(thread)

        for (i in measured.indices) {
            settings = if (i in 10 until 25) automatic else limited
            processor.queueInput(measured[i])
            processor.output
        }

        assertThat(threads.getThreadAllocatedBytes(thread) - before).isEqualTo(0L)
    }

    @Test
    fun `steady processing allocates nothing`() {
        settings = EqualizerSettings(enabled = true, mode = EqualizerMode.TWENTY_BANDS).withGain(EqualizerMode.TWENTY_BANDS, 4, 6f)
        configure(48_000, 2, C.ENCODING_PCM_16BIT)
        val buffers = List(50) { Pcm.encode(sine(440.0, 48_000, 0.02, 0.5, 2), 0, 1_920, C.ENCODING_PCM_16BIT) }
        // Warm up: the first buffers size the scratch arrays and finish the ramp.
        repeat(20) { processor.queueInput(buffers[it].duplicate()); processor.output }
        settings = settings.withGain(EqualizerMode.TWENTY_BANDS, 5, -3f)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        @Suppress("DEPRECATION")
        val thread = Thread.currentThread().id
        val measured = buffers.subList(20, buffers.size).toTypedArray()
        val before = threads.getThreadAllocatedBytes(thread)

        for (i in measured.indices) {
            processor.queueInput(measured[i])
            processor.output
        }

        assertThat(threads.getThreadAllocatedBytes(thread) - before).isEqualTo(0L)
    }
}

/** Independent PCM packing for the tests (interleaved samples in [-1, 1]). */
internal object Pcm {
    val ENCODINGS = listOf(
        C.ENCODING_PCM_8BIT,
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_16BIT_BIG_ENDIAN,
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_24BIT_BIG_ENDIAN,
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_32BIT_BIG_ENDIAN,
        C.ENCODING_PCM_FLOAT,
        C.ENCODING_PCM_FLOAT_BIG_ENDIAN,
    )

    private fun bytes(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        else -> 4
    }

    private fun bigEndian(encoding: Int) = encoding in listOf(
        C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT_BIG_ENDIAN,
    )

    private fun isFloat(encoding: Int) = encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_FLOAT_BIG_ENDIAN

    fun encode(samples: FloatArray, from: Int, count: Int, encoding: Int): ByteBuffer {
        val size = bytes(encoding)
        val order = if (bigEndian(encoding)) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val buffer = ByteBuffer.allocateDirect(count * size).order(order)
        for (i in from until from + count) {
            val s = samples[i].toDouble().coerceIn(-1.0, 1.0)
            when {
                isFloat(encoding) -> buffer.putFloat(samples[i])
                size == 1 -> buffer.put((scaled(s, 7) + 128).toByte())
                size == 2 -> buffer.putShort(scaled(s, 15).toShort())
                size == 3 -> {
                    val v = scaled(s, 23)
                    val b = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
                    if (order == ByteOrder.BIG_ENDIAN) buffer.put(b) else buffer.put(b.reversedArray())
                }
                else -> buffer.putInt(scaled(s, 31))
            }
        }
        buffer.flip()
        return buffer
    }

    /** [s] times 2^[bits], rounded and clamped to a signed `bits + 1`-bit integer (so k / 2^bits round-trips). */
    private fun scaled(s: Double, bits: Int): Int =
        Math.round(s * (1L shl bits)).coerceIn(-(1L shl bits), (1L shl bits) - 1).toInt()

    /** Decodes [buffer] (consumed) into [out] from [offset]; returns the number of samples. */
    fun decode(buffer: ByteBuffer, encoding: Int, out: FloatArray, offset: Int): Int {
        val b = buffer.duplicate().order(if (bigEndian(encoding)) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val size = bytes(encoding)
        val count = b.remaining() / size
        for (i in 0 until count) {
            out[offset + i] = when {
                isFloat(encoding) -> b.float
                size == 1 -> ((b.get().toInt() and 0xFF) - 128) / 128f
                size == 2 -> b.short / 32_768f
                size == 3 -> {
                    val x = ByteArray(3).also { b.get(it) }.let { if (b.order() == ByteOrder.BIG_ENDIAN) it else it.reversedArray() }
                    ((x[0].toInt() shl 16) or ((x[1].toInt() and 0xFF) shl 8) or (x[2].toInt() and 0xFF)) / 8_388_608f
                }
                else -> (b.int / 2_147_483_648.0).toFloat()
            }
        }
        buffer.position(buffer.limit())
        return count
    }
}
