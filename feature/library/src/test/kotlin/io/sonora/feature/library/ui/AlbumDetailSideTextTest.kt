package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.AlbumSummary
import io.sonora.core.domain.library.AudioQuality
import io.sonora.core.domain.library.ReleaseDate
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.XmlStrings
import org.junit.Test

class AlbumDetailSideTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")

    private fun summary(format: String? = "FLAC", year: Int? = 2020, quality: AudioQuality? = AudioQuality.Lossless) =
        AlbumSummary(format, year, quality, ReleaseDate(2020), null, songCount = 1, durationMs = 1)

    private fun track(id: Long, genre: String?) = Track(
        id, "u$id", "t$id", "a", "al", null, 1000, id.toInt(), 1, 2020, 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1,
        genre = genre,
    )

    @Test
    fun `the genre and year line puts the genre first`() {
        assertThat(AlbumDetailText.genreYearLine("Rock", 2020)).isEqualTo("Rock · 2020")
        assertThat(AlbumDetailText.genreYearLine("Rock", null)).isEqualTo("Rock")
        assertThat(AlbumDetailText.genreYearLine(null, 2020)).isEqualTo("2020")
        assertThat(AlbumDetailText.genreYearLine(null, null)).isNull()
    }

    @Test
    fun `the technical line has the format and the quality but not the year`() {
        assertThat(AlbumDetailText.technicalLine(summary(), en)).isEqualTo("FLAC · Lossless")
        assertThat(AlbumDetailText.technicalLine(summary(), es)).isEqualTo("FLAC · Sin pérdida")
        assertThat(AlbumDetailText.technicalLine(summary(format = null), en)).isEqualTo("Lossless")
        assertThat(AlbumDetailText.technicalLine(summary(format = null, quality = null), en)).isNull()
    }

    @Test
    fun `an album's genre is its most common one`() {
        assertThat(albumGenre(listOf(track(1, "Rock"), track(2, "Pop"), track(3, "Rock")))).isEqualTo("Rock")
    }

    @Test
    fun `on a tie the first song's genre wins`() {
        assertThat(albumGenre(listOf(track(1, "Pop"), track(2, "Rock")))).isEqualTo("Pop")
    }

    @Test
    fun `blank and missing genres are ignored`() {
        assertThat(albumGenre(listOf(track(1, null), track(2, "  "), track(3, " Jazz ")))).isEqualTo("Jazz")
        assertThat(albumGenre(listOf(track(1, null), track(2, "")))).isNull()
        assertThat(albumGenre(emptyList())).isNull()
    }
}
