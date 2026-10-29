package io.sonora.core.playback.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.sonora.core.playback.audio.TestAudioFiles.Chunk
import io.sonora.core.playback.audio.TestAudioFiles.aiff
import io.sonora.core.playback.audio.TestAudioFiles.ramp
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiffExtractorTest {

    private fun extract(file: ByteArray, maxReadBytes: Int = 4_093) =
        ExtractorHarness(file, AiffExtractor(), maxReadBytes).apply { extractAll() }

    @Test
    fun `sniffing accepts AIFF and AIFF-C only`() {
        val data = ramp(4, 2, 2)

        assertThat(ExtractorHarness(aiff(2, 44_100, 16, data), AiffExtractor()).sniff()).isTrue()
        assertThat(ExtractorHarness(aiff(2, 44_100, 16, data, compression = "sowt"), AiffExtractor()).sniff()).isTrue()
        val wav = TestAudioFiles.wav(2, 44_100, 16, data, formatType = 1)
        assertThat(ExtractorHarness(wav, AiffExtractor()).sniff()).isFalse()
        assertThat(ExtractorHarness(ByteArray(5), AiffExtractor()).sniff()).isFalse()
    }

    @Test
    fun `16-bit AIFF is output as big-endian PCM with its rate, channels and every sample byte`() {
        val data = ramp(10_000, 2, 2)

        val harness = extract(aiff(2, 44_100, 16, data))

        val format = checkNotNull(harness.format)
        assertThat(format.sampleMimeType).isEqualTo(MimeTypes.AUDIO_RAW)
        assertThat(format.pcmEncoding).isEqualTo(C.ENCODING_PCM_16BIT_BIG_ENDIAN)
        assertThat(format.sampleRate).isEqualTo(44_100)
        assertThat(format.channelCount).isEqualTo(2)
        assertThat(format.averageBitrate).isEqualTo(44_100 * 4 * 8)
        assertThat(harness.tracksEnded).isTrue()
        assertThat(harness.data).isEqualTo(data)
        assertThat(harness.samples.all { it.flags == C.BUFFER_FLAG_KEY_FRAME }).isTrue()
        assertThat(harness.samples.all { it.data.size % 4 == 0 }).isTrue()
        assertThat(harness.samples.maxOf { it.data.size }).isAtMost(format.maxInputSize)
    }

    @Test
    fun `high-resolution and little-endian layouts map to the matching Media3 encodings`() {
        data class Case(val compression: String?, val bits: Int, val bytes: Int, val rate: Int, val encoding: Int)
        val cases = listOf(
            Case(null, 24, 3, 96_000, C.ENCODING_PCM_24BIT_BIG_ENDIAN),
            Case(null, 32, 4, 192_000, C.ENCODING_PCM_32BIT_BIG_ENDIAN),
            Case(null, 20, 3, 48_000, C.ENCODING_PCM_24BIT_BIG_ENDIAN),
            Case("NONE", 24, 3, 88_200, C.ENCODING_PCM_24BIT_BIG_ENDIAN),
            Case("twos", 16, 2, 48_000, C.ENCODING_PCM_16BIT_BIG_ENDIAN),
            Case("sowt", 16, 2, 44_100, C.ENCODING_PCM_16BIT),
            Case("sowt", 24, 3, 96_000, C.ENCODING_PCM_24BIT),
            Case("sowt", 32, 4, 96_000, C.ENCODING_PCM_32BIT),
            Case("in24", 24, 3, 48_000, C.ENCODING_PCM_24BIT_BIG_ENDIAN),
            Case("in32", 32, 4, 48_000, C.ENCODING_PCM_32BIT_BIG_ENDIAN),
            Case("fl32", 32, 4, 192_000, C.ENCODING_PCM_FLOAT_BIG_ENDIAN),
            Case("fl64", 64, 8, 96_000, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN),
        )
        for (case in cases) {
            val data = ramp(1_000, 2, case.bytes)

            val harness = extract(aiff(2, case.rate, case.bits, data, compression = case.compression))

            val format = checkNotNull(harness.format)
            assertThat(format.pcmEncoding).isEqualTo(case.encoding)
            assertThat(format.sampleRate).isEqualTo(case.rate)
            assertThat(harness.data).isEqualTo(data)
            assertThat(checkNotNull(harness.seekMap).durationUs).isEqualTo(1_000 * 1_000_000L / case.rate)
        }
    }

    @Test
    fun `signed 8-bit samples become Media3's unsigned 8-bit and raw stays as it is`() {
        val signed = byteArrayOf(0, 127, -128, -1)

        val aiff8 = extract(aiff(1, 22_050, 8, signed))
        val raw = extract(aiff(1, 22_050, 8, signed, compression = "raw "))

        assertThat(checkNotNull(aiff8.format).pcmEncoding).isEqualTo(C.ENCODING_PCM_8BIT)
        assertThat(aiff8.data).isEqualTo(byteArrayOf(-128, -1, 0, 127))
        assertThat(raw.data).isEqualTo(signed)
    }

    @Test
    fun `chunks around the sound data never reach the output`() {
        val data = ramp(500, 2, 3)
        val id3 = Chunk("ID3 ", ByteArray(200_001) { 0x55 })

        val harness = extract(
            aiff(2, 48_000, 24, data, ssndOffset = 6, before = listOf(Chunk("COMT", ByteArray(3)), id3), after = listOf(id3)),
        )

        assertThat(harness.data).isEqualTo(data)
    }

    @Test
    fun `the widest accepted layout keeps samples bounded and its bitrate exact`() {
        // 768 kHz, 32 channels of 64-bit float: 256-byte frames, so 100 ms would be a 19.6 MB sample.
        val data = ramp(9_000, 32, 8)

        val harness = extract(aiff(32, 768_000, 64, data, compression = "fl64"), maxReadBytes = 65_536)

        val format = checkNotNull(harness.format)
        assertThat(format.maxInputSize).isAtMost(AiffExtractor.MAX_SAMPLE_BYTES)
        assertThat(format.maxInputSize % 256).isEqualTo(0)
        assertThat(format.averageBitrate).isEqualTo(768_000 * 256 * 8)
        assertThat(harness.samples.maxOf { it.data.size }).isAtMost(format.maxInputSize)
        assertThat(harness.data).isEqualTo(data)
    }

    @Test
    fun `a sound chunk stored before the common chunk is read after seeking back`() {
        val data = ramp(300, 1, 2)

        val harness = extract(aiff(1, 44_100, 16, data, soundFirst = true), maxReadBytes = 13)

        assertThat(checkNotNull(harness.format).pcmEncoding).isEqualTo(C.ENCODING_PCM_16BIT_BIG_ENDIAN)
        assertThat(harness.data).isEqualTo(data)
    }

    @Test
    fun `sample times follow the frames before them`() {
        val harness = extract(aiff(2, 48_000, 24, ramp(48_000, 2, 3)))

        var frames = 0L
        for (sample in harness.samples) {
            assertThat(sample.timeUs).isEqualTo(frames * 1_000_000L / 48_000)
            frames += sample.data.size / 6
        }
        assertThat(frames).isEqualTo(48_000)
    }

    @Test
    fun `seeking lands on the frame at the requested time and resumes the data there`() {
        val data = ramp(96_000, 2, 3)
        val file = aiff(2, 96_000, 24, data, before = listOf(Chunk("ID3 ", ByteArray(1_001))))
        val harness = extract(file)
        val seekMap = checkNotNull(harness.seekMap)
        assertThat(seekMap.isSeekable).isTrue()
        assertThat(seekMap.durationUs).isEqualTo(1_000_000)
        val dataStart = file.size - data.size

        // 0.25 s at 96 kHz is frame 24 000; 6 bytes per frame.
        val point = seekMap.getSeekPoints(250_000).first
        assertThat(point.position).isEqualTo(dataStart + 24_000L * 6)
        assertThat(point.timeUs).isEqualTo(250_000)
        // Between frames: the frame at or before the time.
        assertThat(seekMap.getSeekPoints(250_007).first.position).isEqualTo(dataStart + 24_000L * 6)
        assertThat(seekMap.getSeekPoints(-5).first.position).isEqualTo(dataStart.toLong())
        assertThat(seekMap.getSeekPoints(5_000_000).first.position).isEqualTo(dataStart + 95_999L * 6)

        val landed = harness.seekTo(500_000)

        assertThat(landed).isEqualTo(500_000)
        assertThat(harness.samples.first().timeUs).isEqualTo(500_000)
        assertThat(harness.data).isEqualTo(data.copyOfRange(48_000 * 6, data.size))
    }

    @Test
    fun `seeking back to the start rereads from the first frame`() {
        val data = ramp(2_000, 2, 2)
        val harness = extract(aiff(2, 44_100, 16, data, before = listOf(Chunk("ID3 ", ByteArray(64)))))

        harness.seekTo(0)

        assertThat(harness.data).isEqualTo(data)
    }

    @Test
    fun `a file cut inside the sound data ends after the last whole frame`() {
        val data = ramp(1_000, 2, 3)
        val file = aiff(2, 44_100, 24, data)

        val harness = extract(file.copyOf(file.size - 4))

        assertThat(harness.data).isEqualTo(data.copyOf(data.size - 6))
    }

    @Test
    fun `compressed AIFF-C fails as an unsupported container feature`() {
        val error = assertThrows(ParserException::class.java) { extract(aiff(1, 8_000, 16, ByteArray(64), compression = "ima4")) }

        assertThat(error.contentIsMalformed).isFalse()
        assertThat(error).hasMessageThat().contains("ima4")
    }

    @Test
    fun `a header cut short fails as a malformed container`() {
        val file = aiff(2, 44_100, 16, ramp(10, 2, 2))

        val error = assertThrows(ParserException::class.java) { extract(file.copyOf(30)) }

        assertThat(error.contentIsMalformed).isTrue()
    }
}
