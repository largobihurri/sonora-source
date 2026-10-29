package io.sonora.core.decoder

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.audio.PcmStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Decodes the 1 kHz, amplitude 0.5 tones in `assets/fixtures` (see the README there) on the device's codecs. */
@RunWith(AndroidJUnit4::class)
class AndroidPcmDecoderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val decoder = AndroidPcmDecoder(instrumentation.targetContext, Dispatchers.IO)

    private class Decoded(val format: PcmFormat, val frames: Int, val channels: List<FloatArray>, val reads: List<Int>)

    private fun fixture(name: String): String {
        val file = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open("fixtures/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file).toString()
    }

    private fun decode(name: String): Decoded = runBlocking {
        decoder.open(fixture(name)).use { stream ->
            val channelCount = stream.format.channelCount
            val buffer = FloatArray(PcmStream.DEFAULT_CHUNK_FRAMES * channelCount)
            val channels = List(channelCount) { FloatArrayBuilder() }
            val reads = ArrayList<Int>()
            while (true) {
                val frames = stream.read(buffer)
                if (frames < 0) break
                reads += frames
                for (f in 0 until frames) for (c in 0 until channelCount) channels[c].add(buffer[f * channelCount + c])
            }
            Decoded(stream.format, reads.sum(), channels.map { it.toArray() }, reads)
        }
    }

    /** Checks that every channel holds a 1 kHz sine of amplitude 0.5 away from the edges (encoder delay, fades). */
    private fun assertTone(decoded: Decoded, rmsTolerance: Double) {
        assertThat(decoded.reads.all { it in 1..PcmStream.DEFAULT_CHUNK_FRAMES }).isTrue()
        for (samples in decoded.channels) {
            val middle = samples.copyOfRange(samples.size / 5, samples.size * 4 / 5)
            val rms = sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
            val peak = middle.maxOf { abs(it) }
            val crossings = (1 until middle.size).count { (middle[it - 1] < 0f) != (middle[it] < 0f) }
            val frequency = crossings / 2.0 * decoded.format.sampleRateHz / middle.size

            assertThat(rms).isWithin(rmsTolerance).of(0.5 / sqrt(2.0))
            assertThat(peak.toDouble()).isWithin(0.05).of(0.5)
            assertThat(frequency).isWithin(15.0).of(1000.0)
            assertThat(samples.all { it >= -1f && it < 1f }).isTrue()
        }
    }

    @Test
    fun wav16BitMono() {
        val decoded = decode("tone_44k1_s16_mono.wav")

        assertThat(decoded.format.sampleRateHz).isEqualTo(44_100)
        assertThat(decoded.format.channelCount).isEqualTo(1)
        assertThat(decoded.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.INT16)
        assertThat(decoded.frames).isEqualTo(44_100)
        assertTone(decoded, rmsTolerance = 0.002)
    }

    @Test
    fun wav24BitMono() {
        val decoded = decode("tone_48k_s24_mono.wav")

        assertThat(decoded.format.sampleRateHz).isEqualTo(48_000)
        assertThat(decoded.format.channelCount).isEqualTo(1)
        assertThat(decoded.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.INT24)
        assertThat(decoded.frames).isEqualTo(24_000)
        assertTone(decoded, rmsTolerance = 0.002)
    }

    @Test
    fun flac16BitStereo() {
        val decoded = decode("tone_44k1_s16_stereo.flac")

        assertThat(decoded.format.sampleRateHz).isEqualTo(44_100)
        assertThat(decoded.format.channelCount).isEqualTo(2)
        // Some platform FLAC extractors decode to PCM themselves and expose an audio/raw track.
        assertThat(decoded.format.codec).isAnyOf("audio/flac", "audio/raw")
        assertThat(decoded.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.INT16)
        assertThat(decoded.frames).isEqualTo(44_100)
        assertTone(decoded, rmsTolerance = 0.002)
    }

    @Test
    fun flac24BitMono() {
        val decoded = decode("tone_48k_s24_mono.flac")

        assertThat(decoded.format.sampleRateHz).isEqualTo(48_000)
        assertThat(decoded.format.channelCount).isEqualTo(1)
        assertThat(decoded.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.INT24)
        assertThat(decoded.frames).isEqualTo(48_000)
        assertTone(decoded, rmsTolerance = 0.002)
    }

    @Test
    fun mp3Stereo() {
        val decoded = decode("tone_44k1_stereo.mp3")

        assertThat(decoded.format.sampleRateHz).isEqualTo(44_100)
        assertThat(decoded.format.channelCount).isEqualTo(2)
        assertThat(decoded.format.codec).isEqualTo("audio/mpeg")
        assertThat(decoded.format.sourceSampleFormat).isNull()
        assertThat(decoded.frames).isIn(Range.closed(41_000, 48_000))
        assertTone(decoded, rmsTolerance = 0.02)
    }

    @Test
    fun aacM4aStereo() {
        val decoded = decode("tone_44k1_stereo.m4a")

        assertThat(decoded.format.sampleRateHz).isEqualTo(44_100)
        assertThat(decoded.format.channelCount).isEqualTo(2)
        assertThat(decoded.format.codec).isEqualTo("audio/mp4a-latm")
        assertThat(decoded.format.durationUs).isNotNull()
        assertThat(decoded.frames).isIn(Range.closed(41_000, 48_000))
        assertTone(decoded, rmsTolerance = 0.02)
    }

    @Test
    fun aiffIsRecognisedByItsHeader() {
        val decoded = decode("tone_44k1_s16_stereo.aiff")

        assertThat(decoded.format.codec).isEqualTo("AIFF")
        assertThat(decoded.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.INT16)
        assertThat(decoded.format.totalFrames).isEqualTo(11_025)
        assertThat(decoded.frames).isEqualTo(11_025)
        assertTone(decoded, rmsTolerance = 0.002)
    }

    @Test
    fun missingFileIsUnavailable() {
        val uri = Uri.fromFile(File(instrumentation.targetContext.cacheDir, "missing.flac")).toString()

        val error = runCatching { runBlocking { decoder.open(uri) } }.exceptionOrNull()

        assertThat(error).isInstanceOf(PcmDecodeException.SourceUnavailable::class.java)
    }

    @Test
    fun nonAudioFileIsUnsupported() {
        val file = File(instrumentation.targetContext.cacheDir, "notes.txt").apply { writeText("not audio at all\n".repeat(64)) }

        val error = runCatching { runBlocking { decoder.open(Uri.fromFile(file).toString()) } }.exceptionOrNull()

        assertThat(error).isInstanceOf(PcmDecodeException.UnsupportedFormat::class.java)
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(16_384)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
