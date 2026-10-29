package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.AlbumSummary
import io.sonora.core.domain.library.AudioQuality
import io.sonora.core.domain.library.ReleaseDate
import io.sonora.feature.library.XmlStrings
import org.junit.Test

class AlbumDetailTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")
    private val eu = XmlStrings.of("-eu")

    private fun summary(
        format: String? = "FLAC",
        year: Int? = 2020,
        quality: AudioQuality? = AudioQuality.Lossless,
        date: ReleaseDate? = ReleaseDate(2020, 12, 10),
        copyright: String? = "℗ 2020 White World Music",
        songs: Int = 1,
        durationMs: Long = 245_000,
    ) = AlbumSummary(format, year, quality, date, copyright, songs, durationMs)

    @Test
    fun `the metadata line joins format, year and quality`() {
        assertThat(AlbumDetailText.metadataLine(summary(), en)).isEqualTo("FLAC · 2020 · Lossless")
        assertThat(AlbumDetailText.metadataLine(summary(), es)).isEqualTo("FLAC · 2020 · Sin pérdida")
        assertThat(AlbumDetailText.metadataLine(summary(), eu)).isEqualTo("FLAC · 2020 · Galerarik gabe")
    }

    @Test
    fun `high resolution shows depth and rate with the language's decimal separator`() {
        val hiRes = summary(format = "ALAC", quality = AudioQuality.HiRes(24, 96_000))
        assertThat(AlbumDetailText.metadataLine(hiRes, en)).isEqualTo("ALAC · 2020 · 24 bit/96 kHz")
        val cdRate = summary(quality = AudioQuality.HiRes(24, 44_100))
        assertThat(AlbumDetailText.metadataLine(cdRate, en)).isEqualTo("FLAC · 2020 · 24 bit/44.1 kHz")
        assertThat(AlbumDetailText.metadataLine(cdRate, es)).isEqualTo("FLAC · 2020 · 24 bits/44,1 kHz")
    }

    @Test
    fun `lossy albums show their bitrate, approximate when it varies`() {
        assertThat(AlbumDetailText.metadataLine(summary("MP3", quality = AudioQuality.Bitrate(320, false)), en))
            .isEqualTo("MP3 · 2020 · 320 kbps")
        assertThat(AlbumDetailText.metadataLine(summary("AAC", quality = AudioQuality.Bitrate(245, true)), en))
            .isEqualTo("AAC · 2020 · ~245 kbps")
    }

    @Test
    fun `missing parts are left out and an empty line is null`() {
        assertThat(AlbumDetailText.metadataLine(summary(year = null), en)).isEqualTo("FLAC · Lossless")
        assertThat(AlbumDetailText.metadataLine(summary(format = null, quality = null), en)).isEqualTo("2020")
        assertThat(AlbumDetailText.metadataLine(summary(format = null, year = null, quality = null), en)).isNull()
    }

    @Test
    fun `the footer shows the full date, the length and the copyright`() {
        assertThat(AlbumDetailText.footer(summary(), en))
            .containsExactly("December 10, 2020", "1 song, 4 minutes", "℗ 2020 White World Music").inOrder()
        assertThat(AlbumDetailText.footer(summary(), es))
            .containsExactly("10 de diciembre de 2020", "1 canción, 4 minutos", "℗ 2020 White World Music").inOrder()
    }

    @Test
    fun `a year-only date shows the year, and a missing copyright or date is left out`() {
        assertThat(AlbumDetailText.footer(summary(date = ReleaseDate(1999), copyright = null), en))
            .containsExactly("1999", "1 song, 4 minutes").inOrder()
        assertThat(AlbumDetailText.footer(summary(date = ReleaseDate(1999, 7), copyright = null), en))
            .containsExactly("1999", "1 song, 4 minutes").inOrder()
        assertThat(AlbumDetailText.footer(summary(date = null, copyright = null), en)).containsExactly("1 song, 4 minutes")
    }

    @Test
    fun `long albums count hours and minutes`() {
        val long = summary(songs = 12, durationMs = 65 * 60_000L, date = null, copyright = null)
        assertThat(AlbumDetailText.footer(long, en)).containsExactly("12 songs, 1 hour, 5 minutes")
        assertThat(AlbumDetailText.footer(long, eu)).containsExactly("12 abesti, 1 ordu eta 5 minutu")
        val hour = summary(songs = 10, durationMs = 60 * 60_000L, date = null, copyright = null)
        assertThat(AlbumDetailText.footer(hour, en)).containsExactly("10 songs, 1 hour")
    }
}

class NewestFirstTest {
    private fun album(id: Long, year: Int?, added: Long = 0) =
        io.sonora.core.domain.model.Album(id, "A$id", "Band", year, 1, null, dateAddedSec = added)

    @Test
    fun `albums run newest year first, unknown years last, ties by date added`() {
        val sorted = newestFirst(listOf(album(1, 2001), album(2, null), album(3, 2020, added = 1), album(4, 2020, added = 5)))
        assertThat(sorted.map { it.id }).containsExactly(4L, 3L, 1L, 2L).inOrder()
    }
}
