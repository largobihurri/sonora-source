package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmStream
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DecoderPcmSourceTest {

    private val uri = "content://test/track.flac"

    @Test
    fun `exposes the decoded rate and channels`() = runBlocking<Unit> {
        val source = DecoderPcmSource.open(FakeDecoder(format(48_000, 2)), uri)

        assertThat(source.sampleRateHz).isEqualTo(48_000)
        assertThat(source.channelCount).isEqualTo(2)
    }

    @Test
    fun `total frames come from the stream, else from the declared duration, else unknown`() = runBlocking<Unit> {
        val exact = DecoderPcmSource.open(FakeDecoder(format(44_100, 2, totalFrames = 123_456L, durationUs = 9_000_000L)), uri)
        val declared = DecoderPcmSource.open(FakeDecoder(format(48_000, 2, durationUs = 2_500_000L)), uri)
        val unknown = DecoderPcmSource.open(FakeDecoder(format(48_000, 2)), uri)

        assertThat(exact.totalFrames).isEqualTo(123_456L)
        assertThat(declared.totalFrames).isEqualTo(120_000L)
        assertThat(unknown.totalFrames).isNull()
    }

    @Test
    fun `reads pass the stream's frames through until the end`() = runBlocking<Unit> {
        val samples = FloatArray(10) { it / 10f }
        val source = DecoderPcmSource.open(FakeDecoder(format(44_100, 2), samples, framesPerRead = 3), uri)
        val buffer = FloatArray(8)

        val first = source.read(buffer)
        val firstSamples = buffer.copyOf(first * 2)
        val second = source.read(buffer)
        val end = source.read(buffer)

        assertThat(first).isEqualTo(3)
        assertThat(firstSamples).isEqualTo(samples.copyOf(6))
        assertThat(second).isEqualTo(2)
        assertThat(buffer.copyOf(4)).isEqualTo(samples.copyOfRange(6, 10))
        assertThat(end).isEqualTo(-1)
    }

    @Test
    fun `restart reopens the uri and decodes from the first frame`() = runBlocking<Unit> {
        val samples = FloatArray(8) { it.toFloat() }
        val decoder = FakeDecoder(format(44_100, 1), samples, framesPerRead = 8)
        val source = DecoderPcmSource.open(decoder, uri)
        val buffer = FloatArray(8)
        source.read(buffer)
        assertThat(source.read(buffer)).isEqualTo(-1)

        assertThat(source.restart()).isTrue()

        assertThat(source.read(buffer)).isEqualTo(8)
        assertThat(buffer).isEqualTo(samples)
        assertThat(decoder.opened).containsExactly(uri, uri)
        assertThat(decoder.streams.first().closed).isTrue()
    }

    @Test
    fun `restart refuses a reopened stream with another format`() = runBlocking<Unit> {
        val decoder = FakeDecoder(format(44_100, 2))
        val source = DecoderPcmSource.open(decoder, uri)
        decoder.format = format(48_000, 2)

        assertThat(source.restart()).isFalse()
    }

    @Test
    fun `restart refuses when the uri cannot be opened again`() = runBlocking<Unit> {
        val decoder = FakeDecoder(format(44_100, 2))
        val source = DecoderPcmSource.open(decoder, uri)
        decoder.failOpen = true

        assertThat(source.restart()).isFalse()
    }

    @Test
    fun `close closes the stream`() = runBlocking<Unit> {
        val decoder = FakeDecoder(format(44_100, 2))
        val source = DecoderPcmSource.open(decoder, uri)

        source.close()

        assertThat(decoder.streams.single().closed).isTrue()
    }

    private fun format(rate: Int, channels: Int, totalFrames: Long? = null, durationUs: Long? = null) =
        PcmFormat(rate, channels, sourceSampleFormat = null, codec = "test", durationUs = durationUs, totalFrames = totalFrames)

    private class FakeDecoder(
        var format: PcmFormat,
        private val samples: FloatArray = FloatArray(0),
        private val framesPerRead: Int = Int.MAX_VALUE,
    ) : PcmDecoder {
        val opened = ArrayList<String>()
        val streams = ArrayList<FakeStream>()
        var failOpen = false

        override suspend fun open(sourceUri: String): PcmStream {
            if (failOpen) throw io.sonora.core.domain.audio.PcmDecodeException.SourceUnavailable(sourceUri)
            opened += sourceUri
            return FakeStream(format, samples, framesPerRead).also { streams += it }
        }
    }

    private class FakeStream(
        override val format: PcmFormat,
        private val samples: FloatArray,
        private val framesPerRead: Int,
    ) : PcmStream {
        private var at = 0
        var closed = false

        override suspend fun read(buffer: FloatArray): Int {
            check(!closed)
            val channels = format.channelCount
            val left = (samples.size - at) / channels
            if (left <= 0) return -1
            val frames = minOf(left, buffer.size / channels, framesPerRead)
            samples.copyInto(buffer, 0, at, at + frames * channels)
            at += frames * channels
            return frames
        }

        override fun close() {
            closed = true
        }
    }
}
