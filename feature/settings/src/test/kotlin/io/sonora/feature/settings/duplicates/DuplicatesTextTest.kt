package io.sonora.feature.settings.duplicates

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.duplicates.DuplicateGroup
import io.sonora.core.domain.library.duplicates.DuplicateScanState
import io.sonora.feature.settings.XmlStrings
import java.time.ZoneOffset
import org.junit.Test

class DuplicatesTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")

    private val flac = copy("flac", bitsPerSample = 24, sampleRateHz = 96_000, bitrateKbps = 2_400)
    private val mp3 = copy("mp3", format = "MP3", bitrateKbps = 320, bitsPerSample = null, sizeBytes = 9_800_000, folder = "Music/Old", hasArtwork = false, tagsFilled = 3)
    private val group = DuplicateGroup(listOf(flac, mp3))

    @Test
    fun `a group's row says how many copies and why they are duplicates`() {
        assertThat(DuplicatesText.line(group, en)).isEqualTo("2 copies · Same song")
        val identical = DuplicateGroup(listOf(flac.copy(contentHash = "h"), flac.copy(uri = "content://music/b", contentHash = "h")))
        assertThat(DuplicatesText.line(identical, es)).isEqualTo("2 copias · Archivos idénticos")
    }

    @Test
    fun `the scan says what it is doing and what it found`() {
        assertThat(DuplicatesText.status(DuplicateScanState.Scanning(0, 0), en)).isEqualTo("Looking for duplicates…")
        assertThat(DuplicatesText.status(DuplicateScanState.Scanning(340, 1_200), en)).isEqualTo("Comparing files… 340 of 1,200")
        assertThat(DuplicatesText.status(DuplicateScanState.Found(listOf(group)), es)).isEqualTo("1 canción tiene copias")
        assertThat(DuplicatesText.status(DuplicateScanState.Found(emptyList()), en)).isEqualTo("No Duplicates")
        assertThat(DuplicatesText.status(DuplicateScanState.Failed, en)).isEqualTo("Couldn’t look for duplicates. Try again.")
        assertThat(DuplicatesText.status(DuplicateScanState.NotScanned, en)).isNull()
    }

    @Test
    fun `the comparison shows every field per copy and marks the ones that differ`() {
        val rows = DuplicatesText.comparison(group, ZoneOffset.UTC, en).associateBy { it.label }
        assertThat(rows.keys).containsExactly(
            "Format", "Bitrate", "Sample Rate", "Bit Depth", "Channels", "Duration", "Size", "Folder", "File", "Tags", "Artwork", "Added",
        ).inOrder()
        assertThat(rows.getValue("Format")).isEqualTo(ComparisonRow("Format", listOf("FLAC", "MP3"), differs = true))
        assertThat(rows.getValue("Bitrate").values).containsExactly("2,400 kbps", "320 kbps").inOrder()
        assertThat(rows.getValue("Sample Rate").values).containsExactly("96 kHz", "44.1 kHz").inOrder()
        assertThat(rows.getValue("Bit Depth").values).containsExactly("24-bit", "—").inOrder()
        assertThat(rows.getValue("Channels")).isEqualTo(ComparisonRow("Channels", listOf("Stereo", "Stereo"), differs = false))
        assertThat(DuplicatesText.comparison(DuplicateGroup(listOf(flac.copy(channelCount = 6), mp3)), ZoneOffset.UTC, en)[4].values)
            .containsExactly("6 channels", "Stereo").inOrder()
        assertThat(rows.getValue("Duration")).isEqualTo(ComparisonRow("Duration", listOf("4:05", "4:05"), differs = false))
        assertThat(rows.getValue("Size").values).containsExactly("28.6\u00A0MiB", "9.3\u00A0MiB").inOrder()
        assertThat(rows.getValue("Folder").values).containsExactly("Music/Rock", "Music/Old").inOrder()
        assertThat(rows.getValue("Tags").values).containsExactly("7 of 7", "3 of 7").inOrder()
        assertThat(rows.getValue("Artwork").values).containsExactly("Yes", "No").inOrder()
        assertThat(rows.getValue("Added").differs).isFalse()
        assertThat(DuplicatesText.comparison(group, ZoneOffset.UTC, es).first { it.label == "Frecuencia de muestreo" }.values)
            .containsExactly("96 kHz", "44,1 kHz").inOrder()
    }
}
