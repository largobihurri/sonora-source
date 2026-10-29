package io.sonora.core.rekordbox.pdb

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Test

/** The numeric fields a track row stores must fit their on-disk width; bad values are refused, never wrapped. */
class DeviceTrackTest {

    private val track = DeviceTrack(
        id = 1,
        title = "One",
        artist = null,
        album = null,
        tempoBpm = 128.0,
        durationSeconds = 200,
        sampleRate = 44_100,
        bitDepth = 16,
        bitrateKbps = 1411,
        fileSize = 35_000_000,
        filePath = "/Contents/UnknownArtist/UnknownAlbum/One.aiff",
        analyzePath = "/PIONEER/USBANLZ/P001/00000001/ANLZ0000.DAT",
        dateAdded = LocalDate.of(2026, 9, 1),
        trackNumber = 3,
        year = 2025,
    )

    private fun rejected(field: String, build: () -> DeviceTrack) {
        val failure = assertThrows(IllegalArgumentException::class.java) { build() }
        assertThat(failure).hasMessageThat().contains(field)
    }

    @Test
    fun `values at the limits of their fields are accepted`() {
        track.copy(year = 0xFFFF, bitDepth = 0xFFFF, sampleRate = Int.MAX_VALUE, bitrateKbps = Int.MAX_VALUE)
        track.copy(trackNumber = Int.MAX_VALUE, tempoBpm = Int.MAX_VALUE / 100.0, year = null)
        track.copy(trackNumber = 0)
        track.copy(tempoBpm = 0.0, year = 0, sampleRate = 0, bitrateKbps = 0)
    }

    @Test
    fun `a year that does not fit 16 bits is refused`() {
        rejected("year") { track.copy(year = 0x1_0000) }
        rejected("year") { track.copy(year = -1) }
    }

    @Test
    fun `a bit depth that does not fit 16 bits is refused`() {
        rejected("bitDepth") { track.copy(bitDepth = 70_000) }
        rejected("bitDepth") { track.copy(bitDepth = -16) }
    }

    @Test
    fun `negative 32-bit fields are refused`() {
        rejected("sampleRate") { track.copy(sampleRate = -44_100) }
        rejected("bitrateKbps") { track.copy(bitrateKbps = -1) }
        rejected("trackNumber") { track.copy(trackNumber = -2) }
    }

    @Test
    fun `a tempo that is not finite or too large for the stored hundredths is refused`() {
        rejected("tempoBpm") { track.copy(tempoBpm = Double.POSITIVE_INFINITY) }
        rejected("tempoBpm") { track.copy(tempoBpm = Double.NaN) }
        rejected("tempoBpm") { track.copy(tempoBpm = 1e12) }
        rejected("tempoBpm") { track.copy(tempoBpm = -1.0) }
    }
}
