package io.sonora.core.data.library.smart

import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.library.smart.SmartPlaylistRepository
import io.sonora.core.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** [SmartPlaylistRepository] over the library index and its play counts; Room re-emits when either changes. */
@Singleton
class RoomSmartPlaylists @Inject constructor(
    private val dao: LibraryDao,
) : SmartPlaylistRepository {

    override fun recentlyAdded(sinceSec: Long): Flow<List<Track>> = dao.recentlyAddedTracks(sinceSec).toTracks()

    override fun mostPlayed(limit: Int): Flow<List<Track>> = dao.mostPlayedTracks(limit).toTracks()

    override fun neverPlayed(): Flow<List<Track>> = dao.neverPlayedTracks().toTracks()

    private fun Flow<List<TrackEntity>>.toTracks() = map { rows -> rows.map(TrackEntity::toDomain) }.flowOn(Dispatchers.Default)
}
