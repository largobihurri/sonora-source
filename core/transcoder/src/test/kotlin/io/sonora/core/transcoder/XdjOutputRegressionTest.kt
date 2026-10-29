package io.sonora.core.transcoder

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.MetadataTextField
import io.sonora.core.domain.conversion.TrackMetadata
import io.sonora.core.transcoder.dsp.Signals
import io.sonora.core.transcoder.encoder.EncoderRegistry
import io.sonora.core.transcoder.metadata.Id3MetadataChunkSource
import io.sonora.core.transcoder.metadata.Id3v23Writer
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The XDJ-700 output must stay byte for byte what it was before the other formats arrived (converter-formats F4): the
 * CDJ export depends on it. The digests were taken from the 16-bit-only AIFF writer and quantiser.
 */
class XdjOutputRegressionTest {

    private val source = "content://music/track.flac"
    private val metadata = TrackMetadata(text = mapOf(MetadataTextField.TITLE to "Aria", MetadataTextField.ARTIST to "Nadia"))

    private suspend fun convert(stream: FakePcmStream): ByteArray {
        val engine = ConversionEngine(
            FakePcmDecoder(mapOf(source to { stream })),
            EncoderRegistry.default(),
        ) { _, profile ->
            listOf(MetadataChunk(Id3MetadataChunkSource.AIFF_ID3_CHUNK, Id3v23Writer.write(metadata, "Sonora (${profile.displayName})")))
        }
        val output = MemoryChannel()
        engine.convert(source, ConversionProfiles.XDJ_700, output)
        return output.bytes()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a dithered and resampled 96 kHz source gives the same bytes as before`() = runTest {
        val frames = 9_600
        val input = Signals.sines(96_000, frames, 0.5, 1_000.0, 5_000.0)
        val bytes = convert(FakePcmStream(FakePcmStream.format(96_000, 2, PcmSampleFormat.INT24, frames.toLong()), input))

        assertThat(sha256(bytes)).isEqualTo(RESAMPLED_DIGEST)
    }

    @Test
    fun `a 16-bit mono source passes through to the same bytes as before`() = runTest {
        val values = ShortArray(3_001) { ((it * 37) % 65_536 - 32_768).toShort() }
        val input = FloatArray(values.size) { values[it] / 32_768f }
        val bytes = convert(FakePcmStream(FakePcmStream.format(44_100, 1, PcmSampleFormat.INT16, values.size.toLong()), input))

        assertThat(sha256(bytes)).isEqualTo(PASSTHROUGH_DIGEST)
    }

    private companion object {
        const val RESAMPLED_DIGEST = "f0b08effa23ba7f8915271ab809dd4d0b47c2e47ac6c2eaffa7a06346c2442fe"
        const val PASSTHROUGH_DIGEST = "c9ecd9ec0c2661c4bc9a3c390afdda2bcbca408d0d388428e63c8672e8cdaa5b"
    }
}
