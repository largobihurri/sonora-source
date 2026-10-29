package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.DeviceProfile
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import io.sonora.feature.cdjexport.export.ExportConversion.METADATA_ALLOWANCE_BYTES
import org.junit.Test

class ExportConversionTest {

    private val xdj = DeviceProfile.XDJ_700

    private fun plan(audio: SourceAudio) = ExportConversion.plan(audio, xdj)

    private fun aiff(bits: Int?, rate: Int?, channels: Int? = 2, container: AudioContainer = AudioContainer.AIFF) =
        SourceAudio(container, bits, rate, channels)

    @Test
    fun `plain AIFF 16-bit up to 48 kHz goes as it is`() {
        assertThat(plan(aiff(16, 44_100))).isEqualTo(TrackPlan.AsIs)
        assertThat(plan(aiff(16, 48_000, channels = 1))).isEqualTo(TrackPlan.AsIs)
    }

    @Test
    fun `AIFF the player cannot take is converted to 16-bit, keeping rates up to 48 kHz and the channels`() {
        assertThat(plan(aiff(24, 44_100))).isEqualTo(TrackPlan.Convert(ConversionTarget(44_100, 2, 16)))
        assertThat(plan(aiff(32, 48_000, channels = 1))).isEqualTo(TrackPlan.Convert(ConversionTarget(48_000, 1, 16)))
        assertThat(plan(aiff(16, 96_000))).isEqualTo(TrackPlan.Convert(ConversionTarget(44_100, 2, 16)))
        assertThat(plan(aiff(24, 88_200))).isEqualTo(TrackPlan.Convert(ConversionTarget(44_100, 2, 16)))
        assertThat(plan(aiff(16, 32_000))).isEqualTo(TrackPlan.AsIs)
        assertThat(plan(aiff(16, 44_100, channels = 6))).isEqualTo(TrackPlan.Convert(ConversionTarget(44_100, 2, 16)))
    }

    @Test
    fun `AIFF-C the decoder reads is converted, even at 16-bit`() {
        assertThat(plan(SourceAudio(AudioContainer.AIFF_C, 16, 44_100, 2, compression = "sowt")))
            .isEqualTo(TrackPlan.Convert(ConversionTarget(44_100, 2, 16)))
        assertThat(plan(SourceAudio(AudioContainer.AIFF_C, 32, 96_000, 2, compression = "fl32")))
            .isEqualTo(TrackPlan.Convert(ConversionTarget(44_100, 2, 16)))
    }

    @Test
    fun `every other format the decoder reads is converted, the rate decided when it is decoded if unknown`() {
        val formats = listOf(
            AudioContainer.WAV, AudioContainer.FLAC, AudioContainer.MP3, AudioContainer.M4A, AudioContainer.AAC,
            AudioContainer.OGG, AudioContainer.OPUS,
        )
        formats.forEach { assertThat(plan(SourceAudio(it))).isEqualTo(TrackPlan.Convert(ConversionTarget(null, null, 16))) }
        assertThat(plan(SourceAudio(AudioContainer.FLAC, 24, 96_000, 2)))
            .isEqualTo(TrackPlan.Convert(ConversionTarget(44_100, 2, 16)))
    }

    @Test
    fun `files the decoder cannot read are not converted`() {
        // The probe reports only what it found when the AIFF parser refuses a header, as the decoder would.
        assertThat(plan(SourceAudio(AudioContainer.AIFF_C, compression = "ima4"))).isEqualTo(TrackPlan.Undecodable)
        assertThat(plan(SourceAudio(AudioContainer.AIFF, bitsPerSample = 12))).isEqualTo(TrackPlan.Undecodable)
        assertThat(plan(SourceAudio(AudioContainer.AIFF, sampleRateHz = 400_000))).isEqualTo(TrackPlan.Undecodable)
        assertThat(plan(SourceAudio(AudioContainer.UNKNOWN))).isEqualTo(TrackPlan.Undecodable)
    }

    @Test
    fun `the conversion uses the converter's profile for the same player`() {
        assertThat(ExportConversion.conversionProfile(xdj)).isEqualTo(ConversionProfiles.XDJ_700)
    }

    @Test
    fun `room for a converted file is its 16-bit size at the target rate plus room for tags and artwork`() {
        val hiRes = SourceFile("u", "a.aiff", 57_600_000, SourceAudio(AudioContainer.AIFF, 24, 96_000, 2, frameCount = 9_600_000))
        val flac = SourceFile("u", "a.flac", 20_000_000, SourceAudio(AudioContainer.FLAC, 16, 48_000, 2, durationMs = 60_000))
        val mp3 = SourceFile("u", "a.mp3", 2_400_000, SourceAudio(AudioContainer.MP3, durationMs = 60_000))
        val mono = SourceFile("u", "a.wav", 5_292_000, SourceAudio(AudioContainer.WAV, 16, 44_100, 1, durationMs = 60_000))

        assertThat(ExportConversion.exportBytes(hiRes, xdj)).isEqualTo(4_410_000L * 4 + METADATA_ALLOWANCE_BYTES)
        assertThat(ExportConversion.exportBytes(flac, xdj)).isEqualTo(2_880_000L * 4 + METADATA_ALLOWANCE_BYTES)
        // Rate and channels unknown: the largest the profile writes, 48 kHz stereo.
        assertThat(ExportConversion.exportBytes(mp3, xdj)).isEqualTo(2_880_000L * 4 + METADATA_ALLOWANCE_BYTES)
        assertThat(ExportConversion.exportBytes(mono, xdj)).isEqualTo(2_646_000L * 2 + METADATA_ALLOWANCE_BYTES)
    }

    @Test
    fun `without a length, the room is guessed from the file size, generously for lossy formats`() {
        val flac = SourceFile("u", "a.flac", 20_000_000, SourceAudio(AudioContainer.FLAC))
        val aac = SourceFile("u", "a.m4a", 4_000_000, SourceAudio(AudioContainer.M4A))
        val asIs = SourceFile("u", "a.aiff", 30_000_000, SourceAudio(AudioContainer.AIFF, 16, 44_100, 2))

        assertThat(ExportConversion.exportBytes(flac, xdj)).isEqualTo(40_000_000L + METADATA_ALLOWANCE_BYTES)
        assertThat(ExportConversion.exportBytes(aac, xdj)).isEqualTo(48_000_000L + METADATA_ALLOWANCE_BYTES)
        assertThat(ExportConversion.exportBytes(asIs, xdj)).isEqualTo(30_000_000L)
    }
}
