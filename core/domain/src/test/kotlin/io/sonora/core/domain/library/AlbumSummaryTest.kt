package io.sonora.core.domain.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import org.junit.Test

class AlbumSummaryTest {

    private var nextId = 1L

    private fun track(
        codec: String? = "FLAC",
        mimeType: String = "audio/flac",
        bits: Int? = 16,
        rate: Int? = 44_100,
        kbps: Int? = null,
        durationMs: Long = 180_000,
        date: String? = null,
        copyright: String? = null,
        year: Int? = null,
    ) = Track(
        id = nextId++, uri = "content://t/$nextId", title = "T", artist = "A", album = "R", albumArtist = "A",
        durationMs = durationMs, trackNumber = null, discNumber = null, year = year, albumId = 1, artworkUri = null,
        mimeType = mimeType, sizeBytes = 1, sampleRateHz = rate, bitsPerSample = bits, codec = codec, bitrateKbps = kbps,
        releaseDate = date, copyright = copyright,
    )

    @Test
    fun `a CD-quality lossless album is plain Lossless`() {
        val summary = AlbumSummary.of(List(3) { track() }, albumYear = 2020)

        assertThat(summary.format).isEqualTo("FLAC")
        assertThat(summary.year).isEqualTo(2020)
        assertThat(summary.quality).isEqualTo(AudioQuality.Lossless)
    }

    @Test
    fun `a high resolution album shows its most common depth and rate`() {
        val tracks = List(3) { track(codec = "ALAC", bits = 24, rate = 96_000) } + track(codec = "ALAC", bits = 24, rate = 48_000)

        val summary = AlbumSummary.of(tracks, albumYear = null)

        assertThat(summary.format).isEqualTo("ALAC")
        assertThat(summary.quality).isEqualTo(AudioQuality.HiRes(bits = 24, sampleRateHz = 96_000))
    }

    @Test
    fun `24 bit at 44_1 kHz counts as high resolution`() {
        assertThat(AlbumSummary.of(listOf(track(bits = 24, rate = 44_100)), null).quality)
            .isEqualTo(AudioQuality.HiRes(24, 44_100))
    }

    @Test
    fun `a lossy album shows the bitrate most songs share`() {
        val tracks = List(3) { track(codec = "MP3", mimeType = "audio/mpeg", bits = null, kbps = 320) } +
            track(codec = "MP3", mimeType = "audio/mpeg", bits = null, kbps = 256)

        val summary = AlbumSummary.of(tracks, null)

        assertThat(summary.format).isEqualTo("MP3")
        assertThat(summary.quality).isEqualTo(AudioQuality.Bitrate(kbps = 320, approximate = false))
    }

    @Test
    fun `a variable bitrate album shows the rounded average as approximate`() {
        val tracks = listOf(245, 251, 238).map { track(codec = "AAC", mimeType = "audio/mp4", bits = null, kbps = it) }

        assertThat(AlbumSummary.of(tracks, null).quality).isEqualTo(AudioQuality.Bitrate(kbps = 245, approximate = true))
    }

    @Test
    fun `the main format is the one most songs use and quality only looks at those songs`() {
        val tracks = listOf(
            track(codec = "MP3", mimeType = "audio/mpeg", bits = null, kbps = 128),
            track(bits = 24, rate = 96_000),
            track(bits = 24, rate = 96_000),
        )

        val summary = AlbumSummary.of(tracks, null)

        assertThat(summary.format).isEqualTo("FLAC")
        assertThat(summary.quality).isEqualTo(AudioQuality.HiRes(24, 96_000))
    }

    @Test
    fun `songs indexed before codecs were stored fall back to their MIME type`() {
        val tracks = listOf(track(codec = null, mimeType = "audio/mpeg", bits = null, kbps = null))

        val summary = AlbumSummary.of(tracks, null)

        assertThat(summary.format).isEqualTo("MP3")
        assertThat(summary.quality).isNull()
        assertThat(AlbumSummary.of(listOf(track(codec = null, mimeType = "audio/mp4")), null).format).isNull()
    }

    @Test
    fun `release date, copyright, count and length come from the songs`() {
        val tracks = listOf(
            track(date = "2020-12-10", copyright = "℗ 2020 Label", durationMs = 125_000),
            track(date = "2020-12-10", copyright = "℗ 2020 Label", durationMs = 119_000),
            track(date = "2020", durationMs = 1_000),
        )

        val summary = AlbumSummary.of(tracks, albumYear = 2020)

        assertThat(summary.releaseDate).isEqualTo(ReleaseDate(2020, 12, 10))
        assertThat(summary.copyright).isEqualTo("℗ 2020 Label")
        assertThat(summary.songCount).isEqualTo(3)
        assertThat(summary.durationMs).isEqualTo(245_000)
    }

    @Test
    fun `an unreadable date on most songs does not hide a valid date on the others`() {
        val tracks = listOf(track(date = "unknown"), track(date = "unknown"), track(date = "2018-03-02"))

        val summary = AlbumSummary.of(tracks, albumYear = null)

        assertThat(summary.releaseDate).isEqualTo(ReleaseDate(2018, 3, 2))
        assertThat(summary.year).isEqualTo(2018)
    }

    @Test
    fun `dates are counted as dates, and a tie goes to the more precise one`() {
        val padded = listOf(
            track(date = " 2019"), track(date = "2019"), track(date = "2019 "), track(date = "2019-07-01"), track(date = "2019-07-01"),
        )
        assertThat(AlbumSummary.of(padded, albumYear = null).releaseDate).isEqualTo(ReleaseDate(2019))

        val tie = listOf(track(date = "2019"), track(date = "2019-07"), track(date = "2019-07-01"))
        assertThat(AlbumSummary.of(tie, albumYear = null).releaseDate).isEqualTo(ReleaseDate(2019, 7, 1))
    }

    @Test
    fun `without a tagged date the album year stands in, and the year falls back to the date`() {
        assertThat(AlbumSummary.of(listOf(track()), albumYear = 1999).releaseDate).isEqualTo(ReleaseDate(1999))
        assertThat(AlbumSummary.of(listOf(track(date = "2001-05")), albumYear = null).year).isEqualTo(2001)
        val empty = AlbumSummary.of(emptyList(), albumYear = null)
        assertThat(empty.releaseDate).isNull()
        assertThat(empty.format).isNull()
        assertThat(empty.copyright).isNull()
    }

    @Test
    fun `release dates parse only valid parts`() {
        assertThat(ReleaseDate.parse("2020-12-10")).isEqualTo(ReleaseDate(2020, 12, 10))
        assertThat(ReleaseDate.parse("2020-12")).isEqualTo(ReleaseDate(2020, 12))
        assertThat(ReleaseDate.parse("2020")).isEqualTo(ReleaseDate(2020))
        assertThat(ReleaseDate.parse("nonsense")).isNull()
        assertThat(ReleaseDate.parse(null)).isNull()
    }

    @Test
    fun `length in whole minutes rounds and never shows zero for a playable album`() {
        assertThat(AlbumSummary.minutes(245_000)).isEqualTo(4)
        assertThat(AlbumSummary.minutes(29_000)).isEqualTo(1)
        assertThat(AlbumSummary.minutes(0)).isEqualTo(0)
        assertThat(AlbumSummary.minutes(89 * 60_000L + 31_000)).isEqualTo(90)
    }
}
