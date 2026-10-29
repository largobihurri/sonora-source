package io.sonora.player.wear.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackTagsTest {

    private fun tags(
        title: String? = null,
        artist: String? = null,
        albumArtist: String? = null,
        album: String? = null,
        trackNumber: String? = null,
        durationMs: String? = null,
    ) = RawTags(title, artist, albumArtist, album, trackNumber, durationMs)

    @Test
    fun `tags fill every field of the track`() {
        val track = tags("Midnight City", "M83", null, "Hurry Up", "3/12", "243000").toWatchTrack("m.opus", "file:///m.opus", "m.opus")
        assertThat(track).isEqualTo(
            WatchTrack("m.opus", "file:///m.opus", "Midnight City", "M83", "Hurry Up", 3, 243_000L),
        )
    }

    @Test
    fun `a missing or blank title falls back to the file name`() {
        assertThat(tags().toWatchTrack("a", "u", "04 - Intro.opus").title).isEqualTo("Intro")
        assertThat(tags(title = "  ").toWatchTrack("a", "u", "04 - Intro.opus").title).isEqualTo("Intro")
    }

    @Test
    fun `a missing artist falls back to the album artist`() {
        assertThat(tags(albumArtist = "Various").toWatchTrack("a", "u", "x.opus").artist).isEqualTo("Various")
        assertThat(tags(artist = "M83", albumArtist = "Various").toWatchTrack("a", "u", "x.opus").artist).isEqualTo("M83")
        assertThat(tags().toWatchTrack("a", "u", "x.opus").artist).isNull()
    }

    @Test
    fun `values are trimmed`() {
        val track = tags(title = " Song ", artist = " Band ", album = " Album ").toWatchTrack("a", "u", "x.opus")
        assertThat(track.title).isEqualTo("Song")
        assertThat(track.artist).isEqualTo("Band")
        assertThat(track.album).isEqualTo("Album")
    }

    @Test
    fun `a track number reads with or without the total`() {
        assertThat(parseTrackNumber("3")).isEqualTo(3)
        assertThat(parseTrackNumber("03")).isEqualTo(3)
        assertThat(parseTrackNumber("3/12")).isEqualTo(3)
        assertThat(parseTrackNumber(" 7 / 9 ")).isEqualTo(7)
    }

    @Test
    fun `a track number that is missing, zero or not a number is null`() {
        assertThat(parseTrackNumber(null)).isNull()
        assertThat(parseTrackNumber("")).isNull()
        assertThat(parseTrackNumber("0")).isNull()
        assertThat(parseTrackNumber("A1")).isNull()
        assertThat(parseTrackNumber("/12")).isNull()
    }

    @Test
    fun `a duration that is missing, zero or not a number is null`() {
        assertThat(tags(durationMs = null).toWatchTrack("a", "u", "x.opus").durationMs).isNull()
        assertThat(tags(durationMs = "0").toWatchTrack("a", "u", "x.opus").durationMs).isNull()
        assertThat(tags(durationMs = "long").toWatchTrack("a", "u", "x.opus").durationMs).isNull()
    }
}
