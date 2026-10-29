package io.sonora.core.domain.playlist

import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal fun track(id: Long, album: Long = id, artwork: String? = "art:$album", durationMs: Long = 1_000) = Track(
    id = id,
    uri = "content://music/$id",
    title = "Song $id",
    artist = "Artist $id",
    album = "Album $album",
    albumArtist = null,
    durationMs = durationMs,
    trackNumber = null,
    discNumber = null,
    year = null,
    albumId = album,
    artworkUri = artwork,
    mimeType = "audio/flac",
    sizeBytes = 1,
)

internal fun entry(id: Long, trackId: Long) =
    PlaylistEntry(id = id, trackUri = "content://music/$trackId", title = "Song $trackId", artist = "Artist $trackId")

/** In-memory [PlaylistRepository]; entry ids come from one counter, like Room's autoincrement. */
internal class FakePlaylists : PlaylistRepository {
    val state = MutableStateFlow<List<PlaylistWithEntries>>(emptyList())
    private var nextPlaylistId = 1L
    private var nextEntryId = 1L

    /** Like the Room query (`ORDER BY name COLLATE NOCASE, id`): NOCASE folds ASCII letters only. */
    override fun playlists(): Flow<List<PlaylistWithEntries>> = state.map { all ->
        all.sortedWith(compareBy<PlaylistWithEntries> { it.playlist.name.asciiLowercase() }.thenBy { it.playlist.id })
    }

    override fun playlist(id: Long): Flow<PlaylistWithEntries?> = state.map { all -> all.firstOrNull { it.playlist.id == id } }

    override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>): Long {
        val id = nextPlaylistId++
        state.value += PlaylistWithEntries(Playlist(id, name, description), entries.map(::stored))
        return id
    }

    override suspend fun updateDetails(id: Long, name: String, description: String) = change(id) {
        it.copy(playlist = it.playlist.copy(name = name, description = description))
    }

    override suspend fun delete(id: Long) {
        state.value = state.value.filterNot { it.playlist.id == id }
    }

    override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = change(id) {
        // Like Room: a current entry keeps its id the first time it is listed; anything else is a new entry.
        val keepable = it.entries.mapTo(HashSet()) { entry -> entry.id }
        it.copy(entries = edit(it.entries).map { entry -> if (keepable.remove(entry.id)) entry else stored(entry.copy(id = 0)) })
    }

    private fun stored(entry: PlaylistEntry) = if (entry.id == 0L) entry.copy(id = nextEntryId++) else entry

    private fun String.asciiLowercase() = map { c -> if (c in 'A'..'Z') c + ('a' - 'A') else c }.joinToString("")

    private fun change(id: Long, block: (PlaylistWithEntries) -> PlaylistWithEntries) {
        state.value = state.value.map { if (it.playlist.id == id) block(it) else it }
    }
}

/** A library holding exactly [tracks]. */
internal class FakeLibrary(tracks: List<Track>) : LibraryRepository {
    val tracks = MutableStateFlow(tracks)
    override fun tracks(sort: TrackSort): Flow<List<Track>> = tracks
    override fun albums(): Flow<List<Album>> = flowOf(emptyList())
    override fun artists(): Flow<List<Artist>> = flowOf(emptyList())
    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = flowOf(emptyList())
    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = flowOf(emptyList())
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> = flowOf(emptyList())
    override fun search(query: String): Flow<List<Track>> = flowOf(emptyList())
}
