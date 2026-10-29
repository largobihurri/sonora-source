package io.sonora.core.transcoder

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmSampleFormat
import io.sonora.core.domain.conversion.ConversionPlanner
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.transcoder.encoder.EncoderRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** How far a re-decoded output's length may stray from what was written, per container. */
class VerificationToleranceTest {

    private suspend fun verify(profile: ConversionProfile, decodedFrames: Int, written: Long = 44_100): OutputVerification {
        val plan = ConversionPlanner.plan(FakePcmStream.format(44_100, 2, PcmSampleFormat.INT16, null), profile)
        val expected = ConversionResult(plan, outputFrames = written, sourceFrames = written)
        val decoded = "content://music/out"
        val stream = { FakePcmStream(FakePcmStream.format(plan.targetSampleRateHz, 2, null, decodedFrames.toLong()), FloatArray(decodedFrames * 2)) }
        return ConversionEngine(FakePcmDecoder(mapOf(decoded to stream)), EncoderRegistry.default(), MetadataChunkSource.None)
            .verifyDecodes(decoded, expected)
    }

    @Test
    fun `an M4A may decode up to four AAC frames longer or shorter, for the encoder's priming and padding`() = runTest {
        val aac = OutputTargets.profileFor(OutputTarget.Aac())
        // What the platform encoder gives for 1 s at 44.1 kHz: 2048 frames of priming, padded to whole 1024-frame frames.
        assertThat(verify(aac, 47_104)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(aac, 44_100 + 4_096)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(aac, 44_100 - 4_096)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(aac, 44_100 + 4_097)).isInstanceOf(OutputVerification.Invalid::class.java)
        assertThat(verify(aac, 22_050)).isInstanceOf(OutputVerification.Invalid::class.java)
    }

    @Test
    fun `an MP3 may decode up to two MP3 frames off, for decoders that ignore the LAME gapless data`() = runTest {
        val mp3 = OutputTargets.profileFor(OutputTarget.Mp3(Mp3Mode.Vbr(0)))
        assertThat(verify(mp3, 44_100)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(mp3, 44_100 + 2_304)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(mp3, 44_100 - 2_304)).isEqualTo(OutputVerification.Valid)
        assertThat(verify(mp3, 44_100 + 2_305)).isInstanceOf(OutputVerification.Invalid::class.java)
    }

    @Test
    fun `lossless WAV and FLAC outputs must decode to exactly what was written`() = runTest {
        for (format in listOf(AudioFormat.WAV, AudioFormat.FLAC)) {
            val profile = OutputTargets.profileFor(OutputTarget.Lossless(format))
            assertThat(verify(profile, 44_100)).isEqualTo(OutputVerification.Valid)
            assertThat(verify(profile, 44_101)).isInstanceOf(OutputVerification.Invalid::class.java)
            assertThat(verify(profile, 44_099)).isInstanceOf(OutputVerification.Invalid::class.java)
        }
    }
}
