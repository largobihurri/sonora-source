package io.sonora.core.domain.playlist

import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

/**
 * The songs entries can point at, by file URI: the library's, plus [converted] files outside the library folders
 * (the library's own track wins for a file in both). Playlists do not wait for the converted files to be read, and a
 * failed read only leaves those entries unavailable.
 */
private fun playableByUri(library: LibraryRepository, converted: Flow<List<Track>>): Flow<Map<String, Track>> =
    combine(
        library.tracks(),
        converted.onStart { emit(emptyList()) }.catch { emit(emptyList()) },
    ) { tracks, convertedTracks -> convertedTracks.associateBy { it.uri } + tracks.associateBy { it.uri } }

/**
 * Every playlist by name, resolved against the library and the [converted] files ([PlaylistResolution]); re-emits
 * when any changes. The matching runs in [context] (off the main thread by default).
 */
class ObservePlaylists(
    private val playlists: PlaylistRepository,
    private val library: LibraryRepository,
    private val context: CoroutineContext = Dispatchers.Default,
    private val converted: Flow<List<Track>> = flowOf(emptyList()),
) {
    operator fun invoke(): Flow<List<ResolvedPlaylist>> =
        combine(playlists.playlists(), playableByUri(library, converted)) { all, byUri ->
            all.map { PlaylistResolution.resolve(it, byUri) }
        }.flowOn(context)
}

/** Playlist [id] resolved against the library and the [converted] files, in [context]; `null` once it is deleted. */
class ObservePlaylist(
    private val playlists: PlaylistRepository,
    private val library: LibraryRepository,
    private val context: CoroutineContext = Dispatchers.Default,
    private val converted: Flow<List<Track>> = flowOf(emptyList()),
) {
    operator fun invoke(id: Long): Flow<ResolvedPlaylist?> =
        combine(playlists.playlist(id), playableByUri(library, converted)) { playlist, byUri ->
            playlist?.let { PlaylistResolution.resolve(it, byUri) }
        }.flowOn(context)
}

/** Creates a playlist named [name] (trimmed, must not be blank) holding [tracks]; returns its id. */
class CreatePlaylist(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(name: String, description: String = "", tracks: List<Track> = emptyList()): Long {
        val clean = requireNotNull(PlaylistEdits.cleanName(name)) { "A playlist needs a name" }
        return playlists.create(clean, description.trim(), tracks.map(PlaylistEntry::of))
    }
}

/** Renames playlist [id] (the name is trimmed and must not be blank) and sets its description. */
class EditPlaylistDetails(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(id: Long, name: String, description: String) {
        val clean = requireNotNull(PlaylistEdits.cleanName(name)) { "A playlist needs a name" }
        playlists.updateDetails(id, clean, description.trim())
    }
}

class DeletePlaylist(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(id: Long) = playlists.delete(id)
}

/** Appends [tracks] to playlist [id] in order, duplicates included. */
class AddTracksToPlaylist(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(id: Long, tracks: List<Track>) {
        if (tracks.isNotEmpty()) playlists.editEntries(id) { PlaylistEdits.append(it, tracks) }
    }
}

class RemovePlaylistEntry(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(id: Long, entryId: Long) = playlists.editEntries(id) { PlaylistEdits.remove(it, entryId) }
}

/** Moves entry [entryId] of playlist [id] to [toIndex] in the playlist order. */
class MovePlaylistEntry(private val playlists: PlaylistRepository) {
    suspend operator fun invoke(id: Long, entryId: Long, toIndex: Int) =
        playlists.editEntries(id) { PlaylistEdits.move(it, entryId, toIndex) }
}
