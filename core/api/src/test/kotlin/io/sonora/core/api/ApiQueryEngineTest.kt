package io.sonora.core.api

import com.google.common.truth.Truth.assertThat
import io.sonora.core.api.ApiError.NotFound
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState
import io.sonora.core.domain.port.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ApiQueryEngineTest {

    private val songs = listOf(
        track(1, title = "Zebra", albumId = 10, artistId = 5, trackNumber = 2),
        track(2, title = "apple", albumId = 10, artistId = 5, trackNumber = 1),
        track(3, title = "Mango", albumId = 11, artistId = 5, album = "Other"),
        track(4, title = "Needle", albumId = 12, artistId = 6, artist = "Someone"),
    )
    private val albums = listOf(
        Album(id = 11, title = "Other", artist = "Artist", year = 2019, trackCount = 1, artworkUri = "content://art/11", artistId = 5),
        Album(id = 10, title = "Album", artist = "Artist", year = 2020, trackCount = 2, artworkUri = "content://art/10", artistId = 5),
        Album(id = 12, title = "Solo", artist = "Someone", year = null, trackCount = 1, artworkUri = null, artistId = 6),
    )
    private val artists = listOf(
        Artist(id = 6, name = "someone", albumCount = 1, trackCount = 1),
        Artist(id = 5, name = "Artist", albumCount = 2, trackCount = 3),
    )

    private fun rig(playback: FakePlayback = FakePlayback(), playlists: List<PlaylistWithEntries> = emptyList()) =
        Rig(playback = playback, library = FakeLibrary(songs, albums, artists), playlists = FakePlaylists(playlists))

    private fun query(rig: Rig, path: String, vararg params: Pair<String, Any?>) =
        runBlocking { rig.queries.query(path.split('/').filter { it.isNotEmpty() }, mapOf(*params)) }

    private inline fun <reified T : ApiQueryResult> ok(result: ApiQueryResult): T {
        assertThat(result).isInstanceOf(T::class.java)
        return result as T
    }

    @Test
    fun `state shows the player and the settings the API can change`() {
        val rig = Rig(
            playback = FakePlayback(playbackOf(1, 2, 3, current = 1).copy(shuffleEnabled = true, repeatMode = RepeatMode.ALL)),
            sleepTimer = FakeSleepTimer(SleepTimerState(SleepTimerOption.Minutes(15), remainingMs = 600_000)),
            tempo = FakeTempo(PlaybackTempo.DEFAULT.withSpeed(1.25f)),
            crossfade = FakeCrossfade(4),
            djMode = FakeDjMode(true),
            library = FakeLibrary(songs),
        )
        val state = ok<ApiQueryResult.State>(query(rig, "state")).state
        assertThat(state.apiVersion).isEqualTo(API_VERSION)
        assertThat(state.isPlaying).isTrue()
        assertThat(state.positionMs).isEqualTo(12_000)
        assertThat(state.durationMs).isEqualTo(180_000)
        assertThat(state.shuffle).isTrue()
        assertThat(state.repeat).isEqualTo("all")
        assertThat(state.currentIndex).isEqualTo(1)
        assertThat(state.queueSize).isEqualTo(3)
        assertThat(state.currentTrack?.id).isEqualTo(2)
        assertThat(state.sleepTimer).isEqualTo(SleepTimerRow("minutes", 600_000))
        assertThat(state.crossfadeSeconds).isEqualTo(4)
        assertThat(state.speed).isEqualTo(1.25)
        assertThat(state.djMode).isTrue()
    }

    @Test
    fun `state of an idle player has no track and no timer`() {
        val state = ok<ApiQueryResult.State>(query(rig(), "state")).state
        assertThat(state.currentTrack).isNull()
        assertThat(state.sleepTimer).isNull()
        assertThat(state.isPlaying).isFalse()
        assertThat(state.repeat).isEqualTo("off")
        assertThat(state.speed).isEqualTo(1.0)
        assertThat(state.currentIndex).isEqualTo(-1)
    }

    @Test
    fun `the sleep timer modes are named`() {
        fun modeOf(option: SleepTimerOption) = ok<ApiQueryResult.State>(
            query(Rig(sleepTimer = FakeSleepTimer(SleepTimerState(option, null))), "state"),
        ).state.sleepTimer
        assertThat(modeOf(SleepTimerOption.EndOfSong)).isEqualTo(SleepTimerRow("end_of_song", null))
        assertThat(modeOf(SleepTimerOption.EndOfAlbum)).isEqualTo(SleepTimerRow("end_of_album", null))
    }

    @Test
    fun `queue lists the play order with the current item marked`() {
        val rig = rig(FakePlayback(playbackOf(3, 1, 2, current = 1)))
        val page = ok<ApiQueryResult.Queue>(query(rig, "queue")).page
        assertThat(page.rows.map { it.index }).containsExactly(0, 1, 2).inOrder()
        assertThat(page.rows.map { it.track.id }).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(page.rows.map { it.isCurrent }).containsExactly(false, true, false).inOrder()
        assertThat(page.rows.map { it.queueItemId }).containsExactly(100L, 101L, 102L).inOrder()
        assertThat(page.total).isEqualTo(3)
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun `queue pages keep their queue indices`() {
        val rig = rig(FakePlayback(playbackOf(1, 2, 3, 4)))
        val page = ok<ApiQueryResult.Queue>(query(rig, "queue", "limit" to 2, "offset" to 1)).page
        assertThat(page.rows.map { it.index }).containsExactly(1, 2).inOrder()
        assertThat(page).isEqualTo(Page(page.rows, total = 4, limit = 2, offset = 1))
        assertThat(page.hasMore).isTrue()
    }

    @Test
    fun `tracks list by title, ignoring case`() {
        val page = ok<ApiQueryResult.Tracks>(query(rig(), "tracks")).page
        assertThat(page.rows.map { it.title }).containsExactly("apple", "Mango", "Needle", "Zebra").inOrder()
        assertThat(page.total).isEqualTo(4)
    }

    @Test
    fun `tracks search matches title, artist or album`() {
        assertThat(ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "q" to "needle")).page.rows.map { it.id }).containsExactly(4L)
        assertThat(ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "q" to "someone")).page.rows.map { it.id }).containsExactly(4L)
        assertThat(ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "q" to "other")).page.rows.map { it.id }).containsExactly(3L)
        assertThat(ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "q" to "nothing")).page.rows).isEmpty()
    }

    @Test
    fun `tracks page with a total and a more flag`() {
        val first = ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "limit" to 3)).page
        assertThat(first.rows.map { it.title }).containsExactly("apple", "Mango", "Needle").inOrder()
        assertThat(first.total).isEqualTo(4)
        assertThat(first.hasMore).isTrue()
        val last = ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "limit" to 3, "offset" to 3)).page
        assertThat(last.rows.map { it.title }).containsExactly("Zebra")
        assertThat(last.hasMore).isFalse()
    }

    @Test
    fun `an offset past the end is an empty page`() {
        val page = ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "offset" to 99)).page
        assertThat(page.rows).isEmpty()
        assertThat(page.total).isEqualTo(4)
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun `the page size reported is the capped one`() {
        assertThat(ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "limit" to 9_999)).page.limit).isEqualTo(ApiLimits.MAX_PAGE_SIZE)
    }

    @Test
    fun `albums list by title and artists by name`() {
        val albumRows = ok<ApiQueryResult.Albums>(query(rig(), "albums")).page.rows
        assertThat(albumRows.map { it.title }).containsExactly("Album", "Other", "Solo").inOrder()
        assertThat(albumRows.first()).isEqualTo(AlbumRow(10, "Album", "Artist", 5, 2020, 2))
        val artistRows = ok<ApiQueryResult.Artists>(query(rig(), "artists")).page.rows
        assertThat(artistRows.map { it.name }).containsExactly("Artist", "someone").inOrder()
        assertThat(artistRows.first()).isEqualTo(ArtistRow(5, "Artist", 2, 3))
    }

    @Test
    fun `playlists list with their entry count`() {
        val rig = rig(playlists = listOf(playlistOf(8, "Mix", songs[0], songs[1], track(500)), playlistOf(9, "Empty")))
        val rows = ok<ApiQueryResult.Playlists>(query(rig, "playlists")).page.rows
        assertThat(rows).containsExactly(PlaylistRow(8, "Mix", "About Mix", 3), PlaylistRow(9, "Empty", "About Empty", 0)).inOrder()
    }

    @Test
    fun `album tracks come in album order and an unknown album is not found`() {
        val rows = ok<ApiQueryResult.Tracks>(query(rig(), "albums/10/tracks")).page.rows
        assertThat(rows.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(ok<ApiQueryResult.Failed>(query(rig(), "albums/77/tracks")).error).isEqualTo(NotFound("album", 77))
    }

    @Test
    fun `an album that exists but has no songs is an empty page`() {
        val rig = Rig(library = FakeLibrary(albums = albums))
        assertThat(ok<ApiQueryResult.Tracks>(query(rig, "albums/10/tracks")).page.rows).isEmpty()
    }

    @Test
    fun `artist tracks and an unknown artist`() {
        val rows = ok<ApiQueryResult.Tracks>(query(rig(), "artists/5/tracks")).page.rows
        assertThat(rows.map { it.id }).containsExactly(1L, 2L, 3L)
        assertThat(ok<ApiQueryResult.Failed>(query(rig(), "artists/77/tracks")).error).isEqualTo(NotFound("artist", 77))
    }

    @Test
    fun `playlist tracks keep the playlist order and skip files gone from the library`() {
        val rig = rig(playlists = listOf(playlistOf(8, "Mix", songs[3], track(500), songs[0], songs[3])))
        val page = ok<ApiQueryResult.Tracks>(query(rig, "playlists/8/tracks")).page
        assertThat(page.rows.map { it.id }).containsExactly(4L, 1L, 4L).inOrder()
        assertThat(page.total).isEqualTo(3)
        assertThat(ok<ApiQueryResult.Failed>(query(rig, "playlists/77/tracks")).error).isEqualTo(NotFound("playlist", 77))
    }

    @Test
    fun `a parse error comes back as a failed query`() {
        assertThat(ok<ApiQueryResult.Failed>(query(rig(), "nonsense")).error).isEqualTo(ApiError.UnknownQuery)
        assertThat(ok<ApiQueryResult.Failed>(query(rig(), "tracks", "q" to "content://x")).error)
            .isEqualTo(ApiError.LocationRejected("q"))
    }

    @Test
    fun `rows never carry a uri, a path or an artwork location`() {
        val fields = listOf(
            TrackRow::class, AlbumRow::class, ArtistRow::class, PlaylistRow::class, QueueRow::class, StateRow::class,
        ).flatMap { type -> type.java.declaredFields.map { it.name.lowercase() } }
        fields.forEach { name ->
            assertThat(name).doesNotContain("uri")
            assertThat(name).doesNotContain("path")
            assertThat(name).doesNotContain("artwork")
        }
        val row = ok<ApiQueryResult.Tracks>(query(rig(), "tracks", "limit" to 1)).page.rows.single()
        assertThat(row.toString()).doesNotContain("content://")
        assertThat(row.toString()).doesNotContain("secret")
    }

    @Test
    fun `a port that throws becomes a failed query that does not leak the cause`() {
        val broken = Rig(
            library = object : LibraryRepository by FakeLibrary() {
                override fun tracks(sort: TrackSort): Flow<List<Track>> =
                    flow { throw IllegalStateException("/storage/emulated/0/secret") }
            },
        )
        val result = ok<ApiQueryResult.Failed>(query(broken, "tracks"))
        assertThat(result.error).isEqualTo(ApiError.Failed)
        assertThat(result.error.message).doesNotContain("secret")
    }
}
