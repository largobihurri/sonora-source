package io.sonora.core.domain.port

import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read access to the on-device music library. Flows re-emit when the library changes. */
interface LibraryRepository {
    fun tracks(sort: TrackSort = TrackSort.TITLE): Flow<List<Track>>
    fun albums(): Flow<List<Album>>
    fun artists(): Flow<List<Artist>>
    fun recentlyAddedAlbums(limit: Int = 12): Flow<List<Album>>
    fun tracksForAlbum(albumId: Long): Flow<List<Track>>
    fun albumsForArtist(artistId: Long): Flow<List<Album>>

    /** The artist's own songs (as track artist), by title. */
    fun tracksForArtist(artistId: Long): Flow<List<Track>> =
        tracks().map { all -> all.filter { it.artistId == artistId } }

    fun search(query: String): Flow<List<Track>>
}
