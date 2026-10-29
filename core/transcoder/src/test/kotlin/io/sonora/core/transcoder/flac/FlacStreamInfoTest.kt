package io.sonora.core.transcoder.flac

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FlacStreamInfoTest {

    private val body = FlacBlocks.streamInfo(96_000, 2, 24)

    @Test
    fun `the codec's header is found behind the fLaC marker or on its own`() {
        val withMarker = "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 34) + body +
            byteArrayOf(0x84.toByte(), 0, 0, 4) + byteArrayOf(1, 2, 3, 4)

        assertThat(FlacStreamInfo.fromCodecConfig(withMarker)).isEqualTo(body)
        assertThat(FlacStreamInfo.fromCodecConfig(body)).isEqualTo(body)
        assertThat(FlacStreamInfo.fromCodecConfig(byteArrayOf(1, 2, 3))).isNull()
    }

    @Test
    fun `its fields are read from the packed bits`() {
        val info = FlacStreamInfo(body)

        assertThat(info.sampleRateHz).isEqualTo(96_000)
        assertThat(info.channels).isEqualTo(2)
        assertThat(info.bitsPerSample).isEqualTo(24)
    }

    @Test
    fun `the final header carries the real length and unknown frame sizes and MD5`() {
        val stale = body.copyOf().also { it[4] = 9; it[9] = 9; it[20] = 9; it[33] = 9 }

        val final = FlacStreamInfo(stale).withTotalSamples(0x9_1234_5678L)

        assertThat(FlacBlocks.totalSamples(final)).isEqualTo(0x9_1234_5678L)
        assertThat(FlacBlocks.bitsPerSample(final)).isEqualTo(24)
        assertThat(FlacStreamInfo(final).sampleRateHz).isEqualTo(96_000)
        assertThat(final.copyOfRange(4, 10)).isEqualTo(ByteArray(6))
        assertThat(final.copyOfRange(18, 34)).isEqualTo(ByteArray(16))
        assertThat(final.copyOfRange(0, 4)).isEqualTo(body.copyOfRange(0, 4))
    }

    @Test
    fun `lengths beyond 36 bits and short headers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { FlacStreamInfo(body).withTotalSamples(1L shl 36) }
        assertThrows(IllegalArgumentException::class.java) { FlacStreamInfo(ByteArray(33)) }
    }
}
