package io.sonora.core.domain.audio

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.AiffBytes.Companion.hex
import io.sonora.core.domain.audio.PcmLayout.SampleKind.FLOAT
import io.sonora.core.domain.audio.PcmLayout.SampleKind.SIGNED_INT
import io.sonora.core.domain.audio.PcmLayout.SampleKind.UNSIGNED_INT
import java.io.ByteArrayInputStream
import java.nio.ByteOrder.BIG_ENDIAN
import java.nio.ByteOrder.LITTLE_ENDIAN
import org.junit.Assert.assertThrows
import org.junit.Test

class AiffParserTest {

    private fun parse(bytes: ByteArray): AiffHeader = AiffParser.parse(AiffSource.of(ByteArrayInputStream(bytes)))

    private fun aifc() = AiffBytes("AIFC").chunk("FVER", AiffBytes.int32(0xA2805140))

    @Test
    fun `plain AIFF 16-bit stereo gives its format, data position and duration`() {
        val header = parse(AiffBytes().comm(channels = 2, frames = 3, bits = 16).ssnd(ByteArray(12)).build())

        assertThat(header.isAifc).isFalse()
        assertThat(header.channelCount).isEqualTo(2)
        assertThat(header.frameCount).isEqualTo(3)
        assertThat(header.declaredBits).isEqualTo(16)
        assertThat(header.sampleRate).isEqualTo(44_100.0)
        assertThat(header.sampleRateHz).isEqualTo(44_100)
        assertThat(header.compressionType).isEqualTo("NONE")
        assertThat(header.layout).isEqualTo(PcmLayout(SIGNED_INT, 16, BIG_ENDIAN))
        assertThat(header.bytesPerFrame).isEqualTo(4)
        // FORM(12) + COMM(8 + 18) + SSND header(8) + offset and block size(8).
        assertThat(header.dataStart).isEqualTo(54)
        assertThat(header.dataLength).isEqualTo(12)
        assertThat(header.durationUs).isEqualTo(3 * 1_000_000L / 44_100)
    }

    @Test
    fun `80-bit extended sample rates decode exactly`() {
        val rates = mapOf(
            "40 0E AC 44 00 00 00 00 00 00" to 44_100.0,
            "40 0E BB 80 00 00 00 00 00 00" to 48_000.0,
            "40 0F AC 44 00 00 00 00 00 00" to 88_200.0,
            "40 0F BB 80 00 00 00 00 00 00" to 96_000.0,
            "40 10 BB 80 00 00 00 00 00 00" to 192_000.0,
            "40 0B FA 00 00 00 00 00 00 00" to 8_000.0,
        )
        for ((bytes, rate) in rates) {
            val header = parse(AiffBytes().comm(rate = hex(bytes)).ssnd(ByteArray(16)).build())
            assertThat(header.sampleRate).isEqualTo(rate)
        }
    }

    @Test
    fun `integer bit depths keep their container size and big-endian order`() {
        val cases = mapOf(8 to 8, 12 to 16, 16 to 16, 20 to 24, 24 to 24, 32 to 32)
        for ((declared, container) in cases) {
            val bytesPerFrame = container / 8
            val header = parse(AiffBytes().comm(channels = 1, frames = 2, bits = declared).ssnd(ByteArray(2 * bytesPerFrame)).build())
            assertThat(header.declaredBits).isEqualTo(declared)
            assertThat(header.layout).isEqualTo(PcmLayout(SIGNED_INT, container, BIG_ENDIAN))
            assertThat(header.dataLength).isEqualTo(2L * bytesPerFrame)
        }
    }

    @Test
    fun `AIFF-C compression types map to their sample layout`() {
        val cases = listOf(
            Triple("NONE", 24, PcmLayout(SIGNED_INT, 24, BIG_ENDIAN)),
            Triple("twos", 16, PcmLayout(SIGNED_INT, 16, BIG_ENDIAN)),
            Triple("sowt", 16, PcmLayout(SIGNED_INT, 16, LITTLE_ENDIAN)),
            Triple("sowt", 24, PcmLayout(SIGNED_INT, 24, LITTLE_ENDIAN)),
            Triple("sowt", 32, PcmLayout(SIGNED_INT, 32, LITTLE_ENDIAN)),
            Triple("in24", 24, PcmLayout(SIGNED_INT, 24, BIG_ENDIAN)),
            Triple("in32", 32, PcmLayout(SIGNED_INT, 32, BIG_ENDIAN)),
            Triple("fl32", 32, PcmLayout(FLOAT, 32, BIG_ENDIAN)),
            Triple("FL32", 32, PcmLayout(FLOAT, 32, BIG_ENDIAN)),
            Triple("fl64", 64, PcmLayout(FLOAT, 64, BIG_ENDIAN)),
            Triple("FL64", 64, PcmLayout(FLOAT, 64, BIG_ENDIAN)),
            Triple("raw ", 8, PcmLayout(UNSIGNED_INT, 8, BIG_ENDIAN)),
        )
        for ((type, bits, layout) in cases) {
            val header = parse(aifc().comm(channels = 2, frames = 1, bits = bits, compression = type, compressionName = "x").ssnd(ByteArray(64)).build())
            assertThat(header.isAifc).isTrue()
            assertThat(header.compressionType).isEqualTo(type)
            assertThat(header.compressionName).isEqualTo("x")
            assertThat(header.layout).isEqualTo(layout)
            assertThat(header.dataLength).isEqualTo(2L * layout.bytesPerSample)
        }
    }

    @Test
    fun `float compression ignores a zero sample size in COMM`() {
        val header = parse(aifc().comm(channels = 1, frames = 1, bits = 0, compression = "fl32").ssnd(ByteArray(4)).build())

        assertThat(header.layout).isEqualTo(PcmLayout(FLOAT, 32, BIG_ENDIAN))
    }

    @Test
    fun `unknown and odd-sized chunks are skipped with their pad byte`() {
        val bytes = AiffBytes()
            .chunk("COMT", ByteArray(5) { 7 })
            .comm(channels = 1, frames = 2, bits = 16)
            .chunk("MARK", ByteArray(3))
            .ssnd(byteArrayOf(1, 2, 3, 4))
            .build()

        val header = parse(bytes)

        assertThat(header.frameCount).isEqualTo(2)
        assertThat(bytes.copyOfRange(header.dataStart.toInt(), (header.dataStart + header.dataLength).toInt()))
            .isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `a large ID3 chunk with artwork before the sound data is skipped`() {
        val id3 = ByteArray(300_001) { (it % 251).toByte() }
        val bytes = AiffBytes().comm(channels = 1, frames = 2, bits = 16).chunk("ID3 ", id3).ssnd(byteArrayOf(9, 8, 7, 6)).build()

        val header = parse(bytes)

        assertThat(bytes.copyOfRange(header.dataStart.toInt(), (header.dataStart + header.dataLength).toInt()))
            .isEqualTo(byteArrayOf(9, 8, 7, 6))
    }

    @Test
    fun `the SSND offset moves the data start and the data stops at the end of the chunk`() {
        // COMM claims more frames than the chunk holds; the ID3 chunk after it must not be read as audio.
        val bytes = AiffBytes()
            .comm(channels = 1, frames = 100, bits = 16)
            .ssnd(byteArrayOf(1, 2, 3, 4), offset = 4)
            .chunk("ID3 ", ByteArray(10))
            .build()

        val header = parse(bytes)

        assertThat(header.dataStart).isEqualTo(12 + 26 + 16 + 4L)
        assertThat(header.dataLength).isEqualTo(4)
        assertThat(bytes.copyOfRange(header.dataStart.toInt(), (header.dataStart + 4).toInt())).isEqualTo(byteArrayOf(1, 2, 3, 4))
    }

    @Test
    fun `a sound chunk before the common chunk is still located`() {
        val bytes = AiffBytes().ssnd(byteArrayOf(5, 6, 7, 8)).comm(channels = 2, frames = 1, bits = 16).build()

        val header = parse(bytes)

        assertThat(header.dataStart).isEqualTo(28)
        assertThat(header.dataLength).isEqualTo(4)
    }

    @Test
    fun `a zero frame count falls back to the whole frames in the sound chunk`() {
        val header = parse(AiffBytes().comm(channels = 2, frames = 0, bits = 16).ssnd(ByteArray(11)).build())

        assertThat(header.frameCount).isEqualTo(2)
        assertThat(header.dataLength).isEqualTo(8)
    }

    @Test
    fun `a sound chunk declared longer than the file keeps the frame count`() {
        val header = parse(AiffBytes().comm(channels = 1, frames = 3, bits = 16).ssnd(ByteArray(6), declaredSize = 1_000_000).build())

        assertThat(header.dataLength).isEqualTo(6)
    }

    @Test
    fun `truncated streams fail as truncated`() {
        val whole = AiffBytes().comm().chunk("ID3 ", ByteArray(100)).ssnd(ByteArray(16)).build()

        for (length in listOf(0, 4, 11, 20, 30, 60, 150, 158)) {
            assertThrows("length $length", AiffException.Truncated::class.java) { parse(whole.copyOf(length)) }
        }
        val noSound = AiffBytes().comm().build()
        assertThrows(AiffException.Truncated::class.java) { parse(noSound) }
    }

    @Test
    fun `compressed AIFF-C fails with the compression type and name`() {
        for ((type, name) in listOf("ima4" to "IMA 4:1", "ulaw" to "uLaw 2:1", "alaw" to "ALaw 2:1")) {
            val bytes = aifc().comm(compression = type, compressionName = name).ssnd(ByteArray(16)).build()

            val error = assertThrows(AiffException.UnsupportedCompression::class.java) { parse(bytes) }
            assertThat(error.type).isEqualTo(type)
            assertThat(error.name).isEqualTo(name)
        }
    }

    @Test
    fun `sample sizes the layout cannot hold fail as unsupported`() {
        for (bits in listOf(0, 33)) {
            assertThrows(AiffException.UnsupportedSampleSize::class.java) {
                parse(AiffBytes().comm(bits = bits).ssnd(ByteArray(16)).build())
            }
        }
        assertThrows(AiffException.UnsupportedSampleSize::class.java) {
            parse(aifc().comm(bits = 16, compression = "raw ").ssnd(ByteArray(16)).build())
        }
    }

    @Test
    fun `impossible header values fail as malformed`() {
        assertThrows(AiffException.Malformed::class.java) { parse(AiffBytes().comm(channels = 0).ssnd(ByteArray(16)).build()) }
        assertThrows(AiffException.Malformed::class.java) {
            parse(AiffBytes().comm(rate = ByteArray(10)).ssnd(ByteArray(16)).build())
        }
        assertThrows(AiffException.Malformed::class.java) {
            parse(AiffBytes().comm(rate = hex("7F FF 80 00 00 00 00 00 00 00")).ssnd(ByteArray(16)).build())
        }
        assertThrows(AiffException.Malformed::class.java) { parse(AiffBytes().chunk("COMM", ByteArray(10)).ssnd(ByteArray(16)).build()) }
    }

    @Test
    fun `sample rates above 768 kHz and more than 32 channels are rejected as unsupported`() {
        assertThrows(AiffException.UnsupportedSampleRate::class.java) {
            parse(AiffBytes().comm(rate = AiffBytes.extended(768_001)).ssnd(ByteArray(16)).build())
        }
        // 2^65 Hz: would overflow an Int sample rate.
        assertThrows(AiffException.UnsupportedSampleRate::class.java) {
            parse(AiffBytes().comm(rate = hex("40 40 80 00 00 00 00 00 00 00")).ssnd(ByteArray(16)).build())
        }
        assertThrows(AiffException.UnsupportedChannelCount::class.java) {
            parse(AiffBytes().comm(channels = 33, frames = 1).ssnd(ByteArray(66)).build())
        }
        assertThrows(AiffException.UnsupportedChannelCount::class.java) {
            parse(AiffBytes().comm(channels = 65_535, frames = 0).ssnd(ByteArray(0)).build())
        }
    }

    @Test
    fun `768 kHz with 32 channels is the largest accepted layout`() {
        val header = parse(AiffBytes().comm(channels = 32, frames = 1, rate = AiffBytes.extended(768_000)).ssnd(ByteArray(64)).build())

        assertThat(header.sampleRateHz).isEqualTo(768_000)
        assertThat(header.channelCount).isEqualTo(32)
    }

    @Test
    fun `other containers are rejected and recognised by their first bytes`() {
        val wav = "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()

        assertThrows(AiffException.NotAiff::class.java) { parse(wav + ByteArray(32)) }
        assertThat(AiffParser.isAiff(wav)).isFalse()
        assertThat(AiffParser.isAiff(AiffBytes().build())).isTrue()
        assertThat(AiffParser.isAiff(AiffBytes("AIFC").build())).isTrue()
        assertThat(AiffParser.isAiff(AiffBytes("8SVX").build())).isFalse()
        assertThat(AiffParser.isAiff(ByteArray(4))).isFalse()
    }
}
