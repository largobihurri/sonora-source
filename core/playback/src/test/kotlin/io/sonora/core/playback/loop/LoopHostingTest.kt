package io.sonora.core.playback.loop

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djloop.PcmEncoding
import io.sonora.core.djloop.PcmFormat
import org.junit.Test

/** When the loop sink hosts a stream, and the reason it gives when it does not. */
class LoopHostingTest {

    private fun pcm(
        encoding: Int = C.ENCODING_PCM_16BIT,
        rate: Int = 44_100,
        channels: Int = 2,
        mime: String = MimeTypes.AUDIO_RAW,
    ): Format = Format.Builder()
        .setSampleMimeType(mime)
        .setPcmEncoding(encoding)
        .setSampleRate(rate)
        .setChannelCount(channels)
        .build()

    private fun StreamHosting.formatOrNull() = (this as? StreamHosting.Hostable)?.format

    private fun StreamHosting.offReason() = (this as StreamHosting.Unhostable).reason

    @Test
    fun `every PCM encoding the engine holds is hostable`() {
        val expected = mapOf(
            C.ENCODING_PCM_16BIT to PcmEncoding.PCM_16,
            C.ENCODING_PCM_24BIT to PcmEncoding.PCM_24,
            C.ENCODING_PCM_32BIT to PcmEncoding.PCM_32,
            C.ENCODING_PCM_FLOAT to PcmEncoding.FLOAT_32,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to PcmEncoding.PCM_16_BE,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN to PcmEncoding.PCM_24_BE,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN to PcmEncoding.PCM_32_BE,
        )
        for ((c, engine) in expected) {
            val stream = LoopHosting.ofStream(pcm(encoding = c, rate = 48_000), hasChannelMapping = false)
            assertThat(stream.formatOrNull().toString()).isEqualTo(PcmFormat(48_000, 2, engine).toString())
        }
    }

    @Test
    fun `the AIFF decoder's 16-bit big endian PCM is hosted in its own byte order`() {
        val stream = LoopHosting.ofStream(pcm(encoding = C.ENCODING_PCM_16BIT_BIG_ENDIAN, rate = 44_100), hasChannelMapping = false)

        assertThat(stream).isInstanceOf(StreamHosting.Hostable::class.java)
        assertThat(stream.formatOrNull()!!.encoding.bigEndian).isTrue()
        assertThat(stream.formatOrNull()!!.frameBytes).isEqualTo(4)
    }

    @Test
    fun `mono and multichannel PCM are hostable`() {
        assertThat(LoopHosting.ofStream(pcm(channels = 1), false)).isInstanceOf(StreamHosting.Hostable::class.java)
        assertThat(LoopHosting.ofStream(pcm(channels = 6), false)).isInstanceOf(StreamHosting.Hostable::class.java)
    }

    @Test
    fun `a stream that is not raw PCM says so`() {
        assertThat(LoopHosting.ofStream(pcm(mime = MimeTypes.AUDIO_MPEG), false).offReason()).isEqualTo(LoopOff.NOT_RAW_PCM)
        assertThat(LoopHosting.ofStream(pcm(mime = MimeTypes.AUDIO_AC3), false).offReason()).isEqualTo(LoopOff.NOT_RAW_PCM)
    }

    @Test
    fun `a format without a rate or channels says so`() {
        assertThat(LoopHosting.ofStream(pcm(rate = Format.NO_VALUE), false).offReason()).isEqualTo(LoopOff.NO_RATE_OR_CHANNELS)
        assertThat(LoopHosting.ofStream(pcm(channels = Format.NO_VALUE), false).offReason()).isEqualTo(LoopOff.NO_RATE_OR_CHANNELS)
    }

    @Test
    fun `a channel mapping and an encoding the engine does not hold say so`() {
        assertThat(LoopHosting.ofStream(pcm(), hasChannelMapping = true).offReason()).isEqualTo(LoopOff.CHANNEL_MAPPING)
        assertThat(LoopHosting.ofStream(pcm(encoding = C.ENCODING_PCM_8BIT), false).offReason()).isEqualTo(LoopOff.PCM_ENCODING)
        assertThat(LoopHosting.ofStream(pcm(encoding = C.ENCODING_PCM_FLOAT_BIG_ENDIAN), false).offReason()).isEqualTo(LoopOff.PCM_ENCODING)
        assertThat(LoopHosting.ofStream(pcm(encoding = C.ENCODING_PCM_DOUBLE), false).offReason()).isEqualTo(LoopOff.PCM_ENCODING)
        assertThat(LoopHosting.ofStream(pcm(encoding = Format.NO_VALUE), false).offReason()).isEqualTo(LoopOff.PCM_ENCODING)
    }

    @Test
    fun `the unhostable reason names the format it saw`() {
        val stream = LoopHosting.ofStream(pcm(mime = MimeTypes.AUDIO_MPEG, rate = 44_100), false) as StreamHosting.Unhostable

        assertThat(stream.detail).contains("mime=audio/mpeg")
        assertThat(stream.detail).contains("rate=44100")
        assertThat(stream.detail).contains("channels=2")
    }

    @Test
    fun `a hostable stream is hosted with DJ mode on and bit-perfect output not armed`() {
        val stream = LoopHosting.ofStream(pcm(), false)

        val decision = LoopHosting.decide(stream, djMode = true, bitPerfectArmed = false) as LoopHostingDecision.Host
        assertThat(decision.format.toString()).isEqualTo(PcmFormat(44_100, 2, PcmEncoding.PCM_16).toString())
    }

    @Test
    fun `DJ mode off and an armed bit-perfect output each keep a hostable stream out`() {
        val stream = LoopHosting.ofStream(pcm(), false)

        assertThat((LoopHosting.decide(stream, djMode = false, bitPerfectArmed = false) as LoopHostingDecision.PassThrough).reason)
            .isEqualTo(LoopOff.DJ_MODE_OFF)
        assertThat((LoopHosting.decide(stream, djMode = true, bitPerfectArmed = true) as LoopHostingDecision.PassThrough).reason)
            .isEqualTo(LoopOff.BIT_PERFECT_ARMED)
    }

    @Test
    fun `DJ mode off is the reason given before the stream's`() {
        val stream = LoopHosting.ofStream(pcm(mime = MimeTypes.AUDIO_MPEG), false)

        assertThat((LoopHosting.decide(stream, djMode = false, bitPerfectArmed = true) as LoopHostingDecision.PassThrough).reason)
            .isEqualTo(LoopOff.DJ_MODE_OFF)
        assertThat((LoopHosting.decide(stream, djMode = true, bitPerfectArmed = false) as LoopHostingDecision.PassThrough).reason)
            .isEqualTo(LoopOff.NOT_RAW_PCM)
    }

    @Test
    fun `before any stream is configured nothing is hosted`() {
        assertThat((LoopHosting.decide(StreamHosting.NONE, djMode = true, bitPerfectArmed = false) as LoopHostingDecision.PassThrough).reason)
            .isEqualTo(LoopOff.NO_STREAM)
    }

    @Test
    fun `deciding again for the same state gives the same answer without a new object`() {
        val stream = LoopHosting.ofStream(pcm(), false)

        assertThat(LoopHosting.decide(stream, true, false)).isSameInstanceAs(LoopHosting.decide(stream, true, false))
        assertThat(LoopHosting.decide(stream, false, false)).isSameInstanceAs(LoopHosting.decide(stream, false, false))
    }
}
