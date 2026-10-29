package io.sonora.core.rekordbox.timing

import com.google.common.truth.Truth.assertThat
import io.sonora.core.rekordbox.pdb.DeviceFileType
import org.junit.Assert.assertThrows
import org.junit.Test

class RekordboxTimeBaseTest {

    @Test
    fun `lossless formats are already in rekordbox's time base`() {
        for (format in listOf(DeviceFileType.WAV, DeviceFileType.AIFF, DeviceFileType.FLAC)) {
            assertThat(RekordboxTimeBase.toRekordboxMs(1_234.5, format, 0, 44_100)).isEqualTo(1_234.5)
            assertThat(RekordboxTimeBase.offsetMs(format, 0, 44_100)).isEqualTo(0.0)
        }
    }

    @Test
    fun `an MP3 with a LAME header moves later by its encoder delay plus the decoder's 529 frames`() {
        // LAME's usual 576 frames plus 529: 1105 frames at 44.1 kHz, about 25.1 ms (D1.20 measured a median of 26.4 ms).
        val shifted = RekordboxTimeBase.toRekordboxMs(1_000.0, DeviceFileType.MP3, 576, 44_100)

        assertThat(shifted).isWithin(1e-9).of(1_000.0 + 1_105 * 1_000.0 / 44_100)
        assertThat(RekordboxTimeBase.MP3_DECODER_DELAY_FRAMES).isEqualTo(529)
    }

    @Test
    fun `an MP3 without gapless information still moves by the decoder delay`() {
        assertThat(RekordboxTimeBase.offsetMs(DeviceFileType.MP3, 0, 44_100)).isWithin(1e-9).of(529 * 1_000.0 / 44_100)
    }

    @Test
    fun `an AAC file moves later by its priming and nothing else`() {
        // 2112 frames of priming at 44.1 kHz: 47.9 ms (D1.20 measured 48.5 ms on its one m4a).
        assertThat(RekordboxTimeBase.toRekordboxMs(0.0, DeviceFileType.M4A, 2_112, 44_100))
            .isWithin(1e-9).of(2_112 * 1_000.0 / 44_100)
        assertThat(RekordboxTimeBase.offsetMs(DeviceFileType.M4A, 0, 44_100)).isEqualTo(0.0)
    }

    @Test
    fun `the shift follows the sample rate`() {
        assertThat(RekordboxTimeBase.offsetMs(DeviceFileType.MP3, 576, 48_000)).isWithin(1e-9).of(1_105 / 48.0)
        assertThat(RekordboxTimeBase.offsetMs(DeviceFileType.M4A, 1_024, 32_000)).isWithin(1e-9).of(32.0)
    }

    @Test
    fun `toAnalysisMs undoes toRekordboxMs`() {
        val rekordbox = RekordboxTimeBase.toRekordboxMs(12_345.678, DeviceFileType.MP3, 1_105, 48_000)

        assertThat(RekordboxTimeBase.toAnalysisMs(rekordbox, DeviceFileType.MP3, 1_105, 48_000)).isWithin(1e-9).of(12_345.678)
    }

    @Test
    fun `a negative delay or a rate that is not positive is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { RekordboxTimeBase.offsetMs(DeviceFileType.MP3, -1, 44_100) }
        assertThrows(IllegalArgumentException::class.java) { RekordboxTimeBase.offsetMs(DeviceFileType.MP3, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { RekordboxTimeBase.toRekordboxMs(Double.NaN, DeviceFileType.WAV, 0, 44_100) }
    }

    @Test
    fun `a delay on a lossless file is refused rather than guessed`() {
        // No calibration evidence for an edit list on WAV, AIFF or FLAC: such a file is reported, not shifted blindly.
        assertThrows(IllegalArgumentException::class.java) { RekordboxTimeBase.offsetMs(DeviceFileType.FLAC, 10, 44_100) }
    }
}
