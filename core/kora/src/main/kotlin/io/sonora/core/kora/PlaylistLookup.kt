package io.sonora.core.kora

import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.PlaylistResolution
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import kotlinx.coroutines.flow.first

/** The library keyed by the file's URI, which is how playlist entries name their songs. */
internal suspend fun LibraryRepository.byUri(): Map<String, Track> = tracks().first().associateBy { it.uri }

/**
 * What Play plays for playlist [playlistId]: its songs that are still in the library, in playlist order. An id that is
 * not a number, or names no playlist, is an invalid payload.
 */
internal suspend fun playableTracksOf(playlistId: String, library: LibraryRepository, playlists: PlaylistRepository): List<Track> {
    val id = parseId(playlistId, "playlist")
    val playlist = playlists.playlist(id).first() ?: throw invalidPayload("Unknown playlist")
    return PlaylistResolution.resolve(playlist, library.byUri()).playableTracks
}
