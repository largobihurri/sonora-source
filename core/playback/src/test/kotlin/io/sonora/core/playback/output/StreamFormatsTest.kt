package io.sonora.core.playback.output

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.output.SourcePcm
import io.sonora.core.domain.playback.output.StreamFormat
import org.junit.Test

@OptIn(UnstableApi::class)
class StreamFormatsTest {

    private fun raw(encoding: Int, rate: Int = 44_100, channels: Int = 2) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setSampleRate(rate)
        .setChannelCount(channels)
        .build()

    @Test
    fun `every PCM encoding of Media3 maps to its sample type, big-endian ones too`() {
        val expected = mapOf(
            C.ENCODING_PCM_8BIT to SourcePcm.INT8,
            C.ENCODING_PCM_16BIT to SourcePcm.INT16,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to SourcePcm.INT16,
            C.ENCODING_PCM_24BIT to SourcePcm.INT24,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN to SourcePcm.INT24,
            C.ENCODING_PCM_32BIT to SourcePcm.INT32,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN to SourcePcm.INT32,
            C.ENCODING_PCM_FLOAT to SourcePcm.FLOAT32,
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN to SourcePcm.FLOAT32,
        )
        for ((encoding, pcm) in expected) assertThat(StreamFormats.of(raw(encoding))).isEqualTo(StreamFormat(44_100, 2, pcm))
    }

    @Test
    fun `the rate and channel count come along`() {
        assertThat(StreamFormats.of(raw(C.ENCODING_PCM_24BIT, rate = 96_000, channels = 1))).isEqualTo(StreamFormat(96_000, 1, SourcePcm.INT24))
    }

    @Test
    fun `anything that is not raw PCM has no stream format`() {
        val aac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(44_100).setChannelCount(2).build()

        assertThat(StreamFormats.of(aac)).isNull()
    }

    @Test
    fun `a format without a rate or channels has no stream format`() {
        assertThat(StreamFormats.of(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build())).isNull()
    }

    @Test
    fun `the Media3 encoding to ask the sink for matches each mixer encoding`() {
        assertThat(StreamFormats.media3Encoding(io.sonora.core.domain.playback.output.OutputEncoding.PCM_16)).isEqualTo(C.ENCODING_PCM_16BIT)
        assertThat(StreamFormats.media3Encoding(io.sonora.core.domain.playback.output.OutputEncoding.PCM_24_PACKED)).isEqualTo(C.ENCODING_PCM_24BIT)
        assertThat(StreamFormats.media3Encoding(io.sonora.core.domain.playback.output.OutputEncoding.PCM_32)).isEqualTo(C.ENCODING_PCM_32BIT)
        assertThat(StreamFormats.media3Encoding(io.sonora.core.domain.playback.output.OutputEncoding.PCM_FLOAT)).isEqualTo(C.ENCODING_PCM_FLOAT)
        assertThat(StreamFormats.media3Encoding(io.sonora.core.domain.playback.output.OutputEncoding.OTHER)).isEqualTo(C.ENCODING_INVALID)
    }
}
