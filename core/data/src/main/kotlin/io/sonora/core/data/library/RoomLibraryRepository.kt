package io.sonora.core.data.library

import io.sonora.core.data.library.db.AlbumRow
import io.sonora.core.data.library.db.ArtistAlbumCoverRow
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.ArtistCovers
import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [LibraryRepository] over the scanner's Room index; Room re-emits every query when the index (or, for the artists'
 * covers, the play counts) changes.
 */
@Singleton
class RoomLibraryRepository @Inject constructor(
    private val dao: LibraryDao,
) : LibraryRepository {

    override fun tracks(sort: TrackSort): Flow<List<Track>> = when (sort) {
        TrackSort.TITLE -> dao.tracksByTitle()
        TrackSort.ALBUM -> dao.tracksByAlbum()
        TrackSort.ARTIST -> dao.tracksByArtist()
    }.toTracks()

    override fun albums(): Flow<List<Album>> = dao.albums().toAlbums()

    /** Artists with their album covers, most played album first; re-emits when a song is played. */
    override fun artists(): Flow<List<Artist>> =
        combine(dao.artists(), dao.artistAlbumCovers()) { rows, covers ->
            val byArtist = covers.groupBy(ArtistAlbumCoverRow::artistId)
            rows.map { row ->
                row.toDomain().copy(artworkUris = ArtistCovers.pick(byArtist[row.id].orEmpty().map(ArtistAlbumCoverRow::toCover)))
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = dao.recentlyAddedAlbums(limit).toAlbums()

    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = dao.tracksForAlbum(albumId).toTracks()

    override fun albumsForArtist(artistId: Long): Flow<List<Album>> = dao.albumsForArtist(artistId).toAlbums()

    override fun tracksForArtist(artistId: Long): Flow<List<Track>> = dao.tracksForArtist(artistId).toTracks()

    /** Filtered in Kotlin: SQLite LIKE folds ASCII case only, LibraryQuery folds all of Unicode. */
    override fun search(query: String): Flow<List<Track>> =
        if (query.isBlank()) {
            flowOf(emptyList())
        } else {
            dao.tracksByTitle().toTracks().map { LibraryQuery.search(it, query) }.flowOn(Dispatchers.Default)
        }

    private fun Flow<List<TrackEntity>>.toTracks() = map { rows -> rows.map(TrackEntity::toDomain) }.flowOn(Dispatchers.Default)

    private fun Flow<List<AlbumRow>>.toAlbums() = map { rows -> rows.map(AlbumRow::toDomain) }.flowOn(Dispatchers.Default)
}
