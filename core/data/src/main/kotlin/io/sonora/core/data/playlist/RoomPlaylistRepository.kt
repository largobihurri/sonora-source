package io.sonora.core.data.playlist

import io.sonora.core.data.playlist.db.PlaylistDao
import io.sonora.core.data.playlist.db.PlaylistEntity
import io.sonora.core.data.playlist.db.PlaylistEntryEntity
import io.sonora.core.data.playlist.db.PlaylistWithEntryRows
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.port.PlaylistRepository
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [PlaylistRepository] in the app's Room database (tables `playlists` and `playlist_entries`). */
class RoomPlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val clock: Clock,
) : PlaylistRepository {

    override fun playlists(): Flow<List<PlaylistWithEntries>> =
        dao.playlists().map { rows -> rows.map(PlaylistWithEntryRows::toDomain) }

    override fun playlist(id: Long): Flow<PlaylistWithEntries?> = dao.playlist(id).map { it?.toDomain() }

    override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>): Long {
        val now = clock.millis()
        val playlist = PlaylistEntity(name = name, description = description, createdAtMs = now, updatedAtMs = now)
        return dao.insert(playlist, entries.map { PlaylistEntryEntity.of(it.copy(id = 0), playlistId = 0, position = 0) })
    }

    override suspend fun updateDetails(id: Long, name: String, description: String) =
        dao.updateDetails(id, name, description, clock.millis())

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) =
        dao.editEntries(id, clock.millis()) { rows ->
            edit(rows.map(PlaylistEntryEntity::toDomain)).map { PlaylistEntryEntity.of(it, playlistId = id, position = 0) }
        }
}
