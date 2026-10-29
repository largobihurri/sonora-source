package io.sonora.feature.library

import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal fun libraryTrack(id: Long, album: Long = 1, artist: Long = 1) = Track(
    id = id, uri = "content://music/$id", title = "Song $id", artist = "Artist $artist", album = "Album $album",
    albumArtist = null, durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = album,
    artistId = artist, artworkUri = "art:$album", mimeType = "audio/flac", sizeBytes = 1,
)

internal fun album(id: Long, artist: Long) = Album(
    id = id, title = "Album $id", artist = "Artist $artist", year = null, trackCount = 0, artworkUri = null, artistId = artist,
)

/** In-memory [PlaylistRepository]; entry ids come from one counter, like Room's autoincrement. */
internal class FakePlaylistRepository : PlaylistRepository {
    val state = MutableStateFlow<List<PlaylistWithEntries>>(emptyList())
    private var nextPlaylistId = 1L
    private var nextEntryId = 1L

    fun entries(id: Long): List<PlaylistEntry> = state.value.first { it.playlist.id == id }.entries

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

/** A library of [tracks]; albums and artists are derived from the tracks' ids. */
internal class FakeTrackLibrary(private val tracks: List<Track>, private val albums: List<Album> = emptyList()) : LibraryRepository {
    override fun tracks(sort: TrackSort): Flow<List<Track>> = flowOf(tracks)
    override fun albums(): Flow<List<Album>> = flowOf(albums)
    override fun artists(): Flow<List<Artist>> = flowOf(emptyList())
    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = flowOf(emptyList())
    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = flowOf(tracks.filter { it.albumId == albumId })
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> =
        flowOf(albums.filter { a -> a.artistId == artistId || tracks.any { it.albumId == a.id && it.artistId == artistId } })
    override fun search(query: String): Flow<List<Track>> = flowOf(tracks.filter { it.title.contains(query, ignoreCase = true) })
}

/** A folder tree whose every folder holds [tracks]. */
internal class FakeFolderBrowser(private val tracks: List<Track>) : FolderBrowser {
    override fun roots(): Flow<List<FolderEntry>> = emptyFlow()
    override fun contents(folder: FolderRef): Flow<FolderContents> = emptyFlow()
    override suspend fun tracksUnder(folder: FolderRef): List<Track> = tracks
}

/** Records queue calls as "operation:track ids@start" plus "+shuffle" when shuffled. */
internal class RecordingPlaybackController : PlaybackController {
    val calls = mutableListOf<String>()
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
    override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) {
        calls += "playQueue:" + queue.joinToString(",") { it.id.toString() } + "@$startIndex" + if (shuffle) "+shuffle" else ""
    }
    override fun playNow(tracks: List<Track>) = Unit
    override fun playNext(tracks: List<Track>) = Unit
    override fun addToQueue(tracks: List<Track>) = Unit
    override fun moveQueueItem(from: Int, to: Int) = Unit
    override fun removeQueueItem(index: Int) = Unit
    override fun clearUpcoming() = Unit
    override fun skipToQueueItem(index: Int) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun togglePlayPause() = Unit
    override fun skipToNext() = Unit
    override fun skipToPrevious() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffle(enabled: Boolean) = Unit
    override fun setRepeatMode(mode: RepeatMode) = Unit
    override fun cycleRepeatMode() = Unit
}
