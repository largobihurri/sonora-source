package io.sonora.core.domain.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.DeviceProfile.Companion.XDJ_700
import io.sonora.core.domain.export.ProfileRejection.SampleRateTooHigh
import io.sonora.core.domain.export.ProfileRejection.TooManyChannels
import io.sonora.core.domain.export.ProfileRejection.UnknownSampleRate
import io.sonora.core.domain.export.ProfileRejection.UnsupportedBitDepth
import io.sonora.core.domain.export.ProfileRejection.UnsupportedFormat
import org.junit.Test

class DeviceProfileTest {

    private fun aiff(bits: Int? = 16, rate: Int? = 44_100, channels: Int? = 2) =
        SourceAudio(AudioContainer.AIFF, bitsPerSample = bits, sampleRateHz = rate, channelCount = channels)

    @Test
    fun `the XDJ-700 takes 16-bit AIFF at 44,1 and 48 kHz, mono or stereo`() {
        assertThat(XDJ_700.check(aiff(rate = 44_100))).isNull()
        assertThat(XDJ_700.check(aiff(rate = 48_000))).isNull()
        assertThat(XDJ_700.check(aiff(rate = 32_000, channels = 1))).isNull()
    }

    @Test
    fun `24-bit and unknown sample sizes are rejected`() {
        assertThat(XDJ_700.check(aiff(bits = 24))).isEqualTo(UnsupportedBitDepth(24))
        assertThat(XDJ_700.check(aiff(bits = null))).isEqualTo(UnsupportedBitDepth(null))
    }

    @Test
    fun `rates above 48 kHz and unknown rates are rejected`() {
        assertThat(XDJ_700.check(aiff(rate = 96_000))).isEqualTo(SampleRateTooHigh(96_000, 48_000))
        assertThat(XDJ_700.check(aiff(rate = 48_001))).isEqualTo(SampleRateTooHigh(48_001, 48_000))
        assertThat(XDJ_700.check(aiff(rate = null))).isEqualTo(UnknownSampleRate)
    }

    @Test
    fun `other formats are rejected before anything else is looked at`() {
        for (container in listOf(
            AudioContainer.FLAC, AudioContainer.MP3, AudioContainer.WAV, AudioContainer.AIFF_C,
            AudioContainer.M4A, AudioContainer.AAC,
        )) {
            val audio = SourceAudio(container, bitsPerSample = 16, sampleRateHz = 44_100, channelCount = 2)
            assertThat(XDJ_700.check(audio)).isEqualTo(UnsupportedFormat(container))
        }
        assertThat(XDJ_700.check(SourceAudio(AudioContainer.FLAC, bitsPerSample = 24, sampleRateHz = 96_000)))
            .isEqualTo(UnsupportedFormat(AudioContainer.FLAC))
    }

    @Test
    fun `more than two channels are rejected`() {
        assertThat(XDJ_700.check(aiff(channels = 6))).isEqualTo(TooManyChannels(6, 2))
    }

    @Test
    fun `the summary names what the profile takes`() {
        assertThat(XDJ_700.summary).isEqualTo("AIFF 16-bit up to 48 kHz")
        assertThat(XDJ_700.copy(maxSampleRateHz = 44_100).summary).isEqualTo("AIFF 16-bit up to 44.1 kHz")
    }
}
