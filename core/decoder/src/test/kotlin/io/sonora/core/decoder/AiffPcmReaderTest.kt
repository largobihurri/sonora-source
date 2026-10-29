package io.sonora.core.decoder

import com.google.common.truth.Truth.assertThat
import io.sonora.core.decoder.TestAiff.int16Be
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.audio.PcmSamples.MAX_BELOW_ONE
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class AiffPcmReaderTest {

    private val uri = "content://test/tone.aiff"

    private var opened = 0
    private val streams = mutableListOf<TrackingStream>()

    private fun open(bytes: ByteArray): AiffPcmReader = AiffPcmReader.open(uri) {
        opened++
        TrackingStream(ByteArrayInputStream(bytes)).also { streams += it }
    }

    /** Reads to the end with [bufferSize]-sample reads, returning every sample and the frame count of each read. */
    private fun readAll(reader: AiffPcmReader, bufferSize: Int): Pair<FloatArray, List<Int>> {
        val buffer = FloatArray(bufferSize)
        val samples = ArrayList<Float>()
        val reads = ArrayList<Int>()
        while (true) {
            val frames = reader.read(buffer)
            if (frames < 0) break
            reads += frames
            for (i in 0 until frames * reader.format.channelCount) samples += buffer[i]
        }
        return samples.toFloatArray() to reads
    }

    @Test
    fun `16-bit stereo AIFF reports its format and decodes every frame`() {
        val bytes = TestAiff.build(int16Be(0x4000, 0xC000, 0x7FFF, 0x8000, 0, 0), channels = 2, frames = 3, bits = 16)

        open(bytes).use { reader ->
            with(reader.format) {
                assertThat(sampleRateHz).isEqualTo(44_100)
                assertThat(channelCount).isEqualTo(2)
                assertThat(sourceSampleFormat).isEqualTo(PcmSampleFormat.INT16)
                assertThat(codec).isEqualTo("AIFF")
                assertThat(totalFrames).isEqualTo(3)
                assertThat(durationUs).isEqualTo(3 * 1_000_000L / 44_100)
            }
            val (samples, reads) = readAll(reader, 64)

            assertThat(samples).isEqualTo(floatArrayOf(0.5f, -0.5f, 32_767f / 32_768, -1f, 0f, 0f))
            assertThat(reads).containsExactly(3)
        }
    }

    @Test
    fun `24-bit mono AIFF converts exactly`() {
        val data = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte(), 0, 0, 0, 0, 1)
        open(TestAiff.build(data, channels = 1, frames = 3, bits = 24, rate = 48_000)).use { reader ->
            assertThat(reader.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.INT24)
            assertThat(reader.format.sampleRateHz).isEqualTo(48_000)

            assertThat(readAll(reader, 16).first).isEqualTo(floatArrayOf(8_388_607f / 8_388_608, -1f, 1f / 8_388_608))
        }
    }

    @Test
    fun `AIFF-C sowt is little-endian 16-bit`() {
        val data = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0x7F)
        open(TestAiff.build(data, channels = 1, frames = 3, bits = 16, compression = "sowt")).use { reader ->
            assertThat(reader.format.codec).isEqualTo("AIFF-C sowt")
            assertThat(reader.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.INT16)

            assertThat(readAll(reader, 16).first).isEqualTo(floatArrayOf(0.5f, -0.5f, 32_767f / 32_768))
        }
    }

    @Test
    fun `AIFF-C fl32 passes floats through and clips overs`() {
        val data = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putFloat(0.25f).putFloat(-0.75f).putFloat(1.2f).putFloat(-1.5f).array()
        open(TestAiff.build(data, channels = 2, frames = 2, bits = 32, compression = "fl32")).use { reader ->
            assertThat(reader.format.codec).isEqualTo("AIFF-C fl32")
            assertThat(reader.format.sourceSampleFormat).isEqualTo(PcmSampleFormat.FLOAT32)

            assertThat(readAll(reader, 16).first).isEqualTo(floatArrayOf(0.25f, -0.75f, MAX_BELOW_ONE, -1f))
        }
    }

    @Test
    fun `reads are bounded by the buffer and deliver whole frames in order`() {
        val frames = 10_000
        val ramp = IntArray(frames * 2) { (it / 2 % 30_000) * if (it % 2 == 0) 1 else -1 }
        open(TestAiff.build(int16Be(*ramp), channels = 2, frames = frames.toLong(), bits = 16)).use { reader ->
            val (samples, reads) = readAll(reader, 4096 * 2)

            assertThat(reads).containsExactly(4096, 4096, 1808).inOrder()
            assertThat(samples.size).isEqualTo(frames * 2)
            assertThat(samples[2 * 4096]).isEqualTo(4096f / 32_768)
            assertThat(samples[2 * 4096 + 1]).isEqualTo(-4096f / 32_768)
        }
    }

    @Test
    fun `a buffer that is not a multiple of the channel count still gets whole frames`() {
        open(TestAiff.build(int16Be(1, 2, 3, 4, 5, 6, 7, 8), channels = 2, frames = 4, bits = 16)).use { reader ->
            assertThat(readAll(reader, 5).second).containsExactly(2, 2).inOrder()
        }
    }

    @Test
    fun `a buffer smaller than one frame is rejected`() {
        open(TestAiff.build(int16Be(1, 2), channels = 2, frames = 1, bits = 16)).use { reader ->
            assertThrows(IllegalArgumentException::class.java) { reader.read(FloatArray(1)) }
        }
    }

    @Test
    fun `sound data stored before COMM is read from a reopened stream`() {
        val bytes = TestAiff.build(int16Be(0x2000, 0x6000), channels = 1, frames = 2, bits = 16, ssndFirst = true)

        open(bytes).use { reader ->
            assertThat(readAll(reader, 8).first).isEqualTo(floatArrayOf(0.25f, 0.75f))
        }
        assertThat(opened).isEqualTo(2)
        assertThat(streams.all { it.closed }).isTrue()
    }

    @Test
    fun `empty sound data ends at once`() {
        open(TestAiff.build(ByteArray(0), channels = 2, frames = 0, bits = 16)).use { reader ->
            assertThat(reader.format.totalFrames).isEqualTo(0)
            assertThat(reader.read(FloatArray(8))).isEqualTo(-1)
        }
    }

    @Test
    fun `sound data cut short fails as corrupt data`() {
        val whole = TestAiff.build(int16Be(*IntArray(200) { it }), channels = 2, frames = 100, bits = 16)
        val cut = whole.copyOf(whole.size - 51)

        open(cut).use { reader ->
            assertThrows(PcmDecodeException.CorruptData::class.java) { readAll(reader, 64) }
        }
    }

    @Test
    fun `a header cut short fails as corrupt data and closes the stream`() {
        val whole = TestAiff.build(int16Be(1, 2), channels = 1, frames = 2, bits = 16)

        assertThrows(PcmDecodeException.CorruptData::class.java) { open(whole.copyOf(30)) }
        assertThat(streams.single().closed).isTrue()
    }

    @Test
    fun `compressed AIFF-C is an unsupported format`() {
        val bytes = TestAiff.build(ByteArray(34), channels = 1, frames = 64, bits = 16, compression = "ima4")

        val error = assertThrows(PcmDecodeException.UnsupportedFormat::class.java) { open(bytes) }
        assertThat(error.detail).contains("ima4")
    }

    @Test
    fun `a stream that is not AIFF is an unsupported format`() {
        assertThrows(PcmDecodeException.UnsupportedFormat::class.java) { open("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray()) }
    }

    @Test
    fun `a source that cannot be opened is unavailable`() {
        val error = assertThrows(PcmDecodeException.SourceUnavailable::class.java) {
            AiffPcmReader.open(uri) { throw FileNotFoundException("gone") }
        }
        assertThat(error.sourceUri).isEqualTo(uri)
        assertThrows(PcmDecodeException.SourceUnavailable::class.java) {
            AiffPcmReader.open(uri) { throw SecurityException("permission revoked") }
        }
    }

    @Test
    fun `an I-O error while reading samples makes the source unavailable`() {
        val bytes = TestAiff.build(int16Be(*IntArray(400)), channels = 2, frames = 200, bits = 16)
        val reader = AiffPcmReader.open(uri) { FailingAfter(ByteArrayInputStream(bytes), bytes.size - 100) }

        reader.use { assertThrows(PcmDecodeException.SourceUnavailable::class.java) { readAll(it, 64) } }
    }

    @Test
    fun `close closes the stream`() {
        open(TestAiff.build(int16Be(1), channels = 1, frames = 1, bits = 16)).close()

        assertThat(streams.single().closed).isTrue()
    }

    private class TrackingStream(input: InputStream) : FilterInputStream(input) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** Serves the first [limit] bytes, then fails like a revoked descriptor. */
    private class FailingAfter(input: InputStream, private var limit: Int) : FilterInputStream(input) {
        override fun read(): Int = if (limit-- > 0) super.read() else throw IOException("descriptor revoked")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) throw IOException("descriptor revoked")
            val read = super.read(b, off, minOf(len, limit))
            if (read > 0) limit -= read
            return read
        }
    }
}
