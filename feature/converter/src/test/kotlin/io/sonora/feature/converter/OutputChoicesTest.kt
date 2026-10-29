package io.sonora.feature.converter

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.model.AudioFormat
import org.junit.Test

class OutputChoicesTest {

    @Test
    fun `the picker lists the XDJ-700 and every format, never the watch's profile`() {
        assertThat(FormatChoice.entries.map { it.format }).containsExactly(
            null, AudioFormat.AIFF, AudioFormat.WAV, AudioFormat.FLAC, AudioFormat.AAC_M4A, AudioFormat.OPUS, AudioFormat.MP3,
        ).inOrder()
        assertThat(FormatChoice.entries.map { it.pick(OutputTarget.Xdj700).id }).doesNotContain(ConversionProfiles.WATCH_OPUS.id)
    }

    @Test
    fun `each target shows as its format`() {
        assertThat(FormatChoice.of(OutputTarget.Xdj700)).isEqualTo(FormatChoice.XDJ_700)
        assertThat(FormatChoice.of(OutputTarget.Lossless(AudioFormat.AIFF, 24))).isEqualTo(FormatChoice.AIFF)
        assertThat(FormatChoice.of(OutputTarget.Lossless(AudioFormat.WAV))).isEqualTo(FormatChoice.WAV)
        assertThat(FormatChoice.of(OutputTarget.Lossless(AudioFormat.FLAC))).isEqualTo(FormatChoice.FLAC)
        assertThat(FormatChoice.of(OutputTarget.Aac())).isEqualTo(FormatChoice.AAC)
        assertThat(FormatChoice.of(OutputTarget.Opus())).isEqualTo(FormatChoice.OPUS)
        assertThat(FormatChoice.of(OutputTarget.Mp3(Mp3Mode.Vbr(2)))).isEqualTo(FormatChoice.MP3)
    }

    @Test
    fun `picking another format starts on its default settings`() {
        val current = OutputTarget.Lossless(AudioFormat.FLAC, 24, 96_000)

        assertThat(FormatChoice.WAV.pick(current)).isEqualTo(OutputTarget.Lossless(AudioFormat.WAV, 16, null))
        assertThat(FormatChoice.AAC.pick(current)).isEqualTo(OutputTarget.Aac(256))
        assertThat(FormatChoice.OPUS.pick(current)).isEqualTo(OutputTarget.Opus(160))
        assertThat(FormatChoice.MP3.pick(current)).isEqualTo(OutputTarget.Mp3(Mp3Mode.Cbr(320)))
        assertThat(FormatChoice.XDJ_700.pick(current)).isEqualTo(OutputTarget.Xdj700)
    }

    @Test
    fun `picking the format already chosen keeps its settings`() {
        val flac = OutputTarget.Lossless(AudioFormat.FLAC, 24, 96_000)
        val vbr = OutputTarget.Mp3(Mp3Mode.Vbr(4))

        assertThat(FormatChoice.FLAC.pick(flac)).isEqualTo(flac)
        assertThat(FormatChoice.MP3.pick(vbr)).isEqualTo(vbr)
    }

    @Test
    fun `switching MP3 between constant and variable bitrate starts on that mode's default`() {
        assertThat(OutputChoices.withMp3Vbr(OutputTarget.Mp3(Mp3Mode.Cbr(192)), vbr = true)).isEqualTo(OutputTarget.Mp3(Mp3Mode.Vbr(0)))
        assertThat(OutputChoices.withMp3Vbr(OutputTarget.Mp3(Mp3Mode.Vbr(4)), vbr = false)).isEqualTo(OutputTarget.Mp3(Mp3Mode.Cbr(320)))
        // Already on that mode: unchanged.
        assertThat(OutputChoices.withMp3Vbr(OutputTarget.Mp3(Mp3Mode.Vbr(4)), vbr = true)).isEqualTo(OutputTarget.Mp3(Mp3Mode.Vbr(4)))
    }
}
