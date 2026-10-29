package io.sonora.core.domain.stats

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.plays.TrackPlays
import io.sonora.core.domain.model.Track
import org.junit.Test

class ListeningStatsTest {

    private fun track(
        id: Long,
        title: String = "Song $id",
        artist: String = "Artist",
        album: String = "Album",
        albumId: Long = 1,
        albumArtist: String? = null,
    ) = Track(
        id = id, uri = "content://music/$id", title = title, artist = artist, album = album, albumArtist = albumArtist,
        durationMs = 200_000, trackNumber = null, discNumber = null, year = null, albumId = albumId,
        artworkUri = "art:$albumId", mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun heard(id: Long, plays: Int, listenedMs: Long) = TrackListening("content://music/$id", plays, listenedMs)

    @Test
    fun `each range starts a fixed span before now, all time has no start`() {
        val now = 1_790_000_000_000L
        val day = 24 * 60 * 60 * 1000L

        assertThat(StatsRange.WEEK.startMs(now)).isEqualTo(now - 7 * day)
        assertThat(StatsRange.MONTH.startMs(now)).isEqualTo(now - 30 * day)
        assertThat(StatsRange.YEAR.startMs(now)).isEqualTo(now - 365 * day)
        assertThat(StatsRange.ALL_TIME.startMs(now)).isNull()
    }

    @Test
    fun `totals count every song heard, the top lists only songs still in the library`() {
        val tracks = listOf(track(1))
        val listening = listOf(heard(1, 2, 400_000), TrackListening("content://gone", 1, 100_000))

        val stats = listeningStats(StatsRange.WEEK, listening, emptyMap(), tracks)

        assertThat(stats.listenedMs).isEqualTo(500_000)
        assertThat(stats.plays).isEqualTo(3)
        assertThat(stats.songs.map { it.key }).containsExactly("content://music/1")
    }

    @Test
    fun `older play counts add to all time only, never twice with the recorded plays`() {
        val tracks = listOf(track(1), track(2), track(3))
        val listening = listOf(heard(1, 2, 400_000), heard(2, 3, 600_000))
        // Song 1 was played 5 times in all, 2 of them since listening is recorded; song 2's count lags behind.
        val counts = mapOf(
            "content://music/1" to TrackPlays(5, 0),
            "content://music/2" to TrackPlays(1, 0),
            "content://music/3" to TrackPlays(4, 0),
        )

        val allTime = listeningStats(StatsRange.ALL_TIME, listening, counts, tracks)
        val month = listeningStats(StatsRange.MONTH, listening, counts, tracks)

        assertThat(allTime.plays).isEqualTo(5 + 3 + 4)
        assertThat(allTime.listenedMs).isEqualTo(1_000_000)
        assertThat(allTime.songs.map { it.title to it.plays }).containsExactly("Song 1" to 5, "Song 3" to 4, "Song 2" to 3).inOrder()
        assertThat(month.plays).isEqualTo(5)
        assertThat(month.songs.map { it.title }).containsExactly("Song 2", "Song 1").inOrder()
    }

    @Test
    fun `ties on plays go to the most heard, then by title`() {
        val tracks = listOf(track(1, title = "beta"), track(2, title = "Alpha"), track(3, title = "Gamma"), track(4, title = "Delta"))
        val listening = listOf(heard(1, 2, 300_000), heard(2, 2, 300_000), heard(3, 2, 500_000), heard(4, 3, 10_000))

        val stats = listeningStats(StatsRange.WEEK, listening, emptyMap(), tracks)

        assertThat(stats.songs.map { it.title }).containsExactly("Delta", "Gamma", "Alpha", "beta").inOrder()
    }

    @Test
    fun `artists and albums add up their songs, named as their most played song names them`() {
        val tracks = listOf(
            track(1, artist = "Nova", album = "First", albumId = 10),
            track(2, artist = "nova ", album = "First", albumId = 10),
            track(3, artist = "Guest", album = "Mixes", albumId = 20, albumArtist = "Various Artists"),
            track(4, artist = "", album = "Mixes", albumId = 20, albumArtist = "Various Artists"),
        )
        val listening = listOf(heard(1, 2, 100_000), heard(2, 1, 150_000), heard(3, 4, 50_000), heard(4, 1, 20_000))

        val stats = listeningStats(StatsRange.WEEK, listening, emptyMap(), tracks)

        assertThat(stats.artists).containsExactly(
            RankedItem("guest", "Guest", null, "art:20", 4, 50_000),
            RankedItem("nova", "Nova", null, "art:10", 3, 250_000),
        ).inOrder()
        assertThat(stats.albums).containsExactly(
            RankedItem("20", "Mixes", "Various Artists", "art:20", 5, 70_000),
            RankedItem("10", "First", "Nova", "art:10", 3, 250_000),
        ).inOrder()
        assertThat(stats.songs.first()).isEqualTo(RankedItem("content://music/3", "Song 3", "Guest", "art:20", 4, 50_000))
    }

    @Test
    fun `songs only skipped through count toward the totals but never make a top list`() {
        val tracks = listOf(
            track(1, artist = "Nova", albumId = 10),
            track(2, artist = "Nova", albumId = 10),
            track(3, artist = "Skipped", albumId = 20),
        )
        // Song 2 and song 3 were only heard for a few seconds: no play was counted.
        val listening = listOf(heard(1, 1, 200_000), heard(2, 0, 5_000), heard(3, 0, 20_000))

        val stats = listeningStats(StatsRange.WEEK, listening, emptyMap(), tracks)

        assertThat(stats.listenedMs).isEqualTo(225_000)
        assertThat(stats.plays).isEqualTo(1)
        assertThat(stats.songs.map { it.key }).containsExactly("content://music/1")
        assertThat(stats.artists.map { it.key }).containsExactly("nova")
        assertThat(stats.albums.map { it.key }).containsExactly("10")
        assertThat(stats.artists.single().listenedMs).isEqualTo(205_000)
    }

    @Test
    fun `each top list keeps ten`() {
        val tracks = (1L..12L).map { track(it, artist = "Artist $it", albumId = it) }
        val listening = (1L..12L).map { heard(it, it.toInt(), 1_000) }

        val stats = listeningStats(StatsRange.YEAR, listening, emptyMap(), tracks)

        assertThat(stats.songs).hasSize(10)
        assertThat(stats.artists).hasSize(10)
        assertThat(stats.albums).hasSize(10)
        assertThat(stats.songs.first().plays).isEqualTo(12)
        assertThat(stats.songs.last().plays).isEqualTo(3)
    }

    @Test
    fun `nothing heard gives empty statistics`() {
        assertThat(listeningStats(StatsRange.ALL_TIME, emptyList(), emptyMap(), listOf(track(1)))).isEqualTo(ListeningStats())
    }
}
