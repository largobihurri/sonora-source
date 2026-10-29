package io.sonora.core.playback.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.wav.WavExtractor
import com.google.common.truth.Truth.assertThat
import io.sonora.core.playback.audio.TestAudioFiles.ramp
import io.sonora.core.playback.audio.TestAudioFiles.wav
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Media3's own WAV extractor already reads every high-resolution WAV layout the library holds (plain, IEEE float and
 * WAVE_FORMAT_EXTENSIBLE with a PCM or float subformat) at full resolution; this pins that down for 1.11.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WavHighResolutionTest {

    private data class Case(val formatType: Int, val subFormat: Int, val bits: Int, val rate: Int, val encoding: Int)

    @Test
    fun `high-resolution WAV layouts keep their bit depth, sample rate and samples`() {
        val cases = listOf(
            Case(1, 1, 16, 44_100, C.ENCODING_PCM_16BIT),
            Case(1, 1, 24, 96_000, C.ENCODING_PCM_24BIT),
            Case(1, 1, 32, 192_000, C.ENCODING_PCM_32BIT),
            Case(3, 3, 32, 192_000, C.ENCODING_PCM_FLOAT),
            Case(0xFFFE, 1, 24, 96_000, C.ENCODING_PCM_24BIT),
            Case(0xFFFE, 1, 32, 48_000, C.ENCODING_PCM_32BIT),
            Case(0xFFFE, 3, 32, 96_000, C.ENCODING_PCM_FLOAT),
            Case(3, 3, 64, 48_000, C.ENCODING_PCM_DOUBLE),
        )
        for (case in cases) {
            val data = ramp(2_000, 2, case.bits / 8)
            val harness = ExtractorHarness(wav(2, case.rate, case.bits, data, case.formatType, case.subFormat), WavExtractor())

            assertThat(harness.sniff()).isTrue()
            harness.extractAll()

            val format = checkNotNull(harness.format) { "no format for $case" }
            assertThat(format.pcmEncoding).isEqualTo(case.encoding)
            assertThat(format.sampleRate).isEqualTo(case.rate)
            assertThat(format.channelCount).isEqualTo(2)
            assertThat(harness.data).isEqualTo(data)
        }
    }
}
