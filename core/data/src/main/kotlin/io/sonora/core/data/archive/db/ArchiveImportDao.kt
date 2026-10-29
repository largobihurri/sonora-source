package io.sonora.core.data.archive.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

private const val WITH_COUNT = "SELECT i.*, COUNT(s.id) AS song_count FROM archive_imports i " +
    "LEFT JOIN archive_import_songs s ON s.import_id = i.id"

/** The archive import history (tables `archive_imports` and `archive_import_songs`). */
@Dao
abstract class ArchiveImportDao {

    @Insert
    protected abstract suspend fun insertImport(entry: ArchiveImportEntity): Long

    @Insert
    protected abstract suspend fun insertSongs(songs: List<ArchiveImportSongEntity>)

    /** Stores [entry] and its [songs] (their import id and position are set here, in order) together; returns its id. */
    @Transaction
    open suspend fun insert(entry: ArchiveImportEntity, songs: List<ArchiveImportSongEntity>): Long {
        val id = insertImport(entry.copy(id = 0))
        insertSongs(songs.mapIndexed { position, song -> song.copy(id = 0, importId = id, position = position) })
        return id
    }

    @Query("$WITH_COUNT GROUP BY i.id ORDER BY i.imported_at_ms DESC, i.id DESC")
    abstract fun imports(): Flow<List<ImportWithCount>>

    @Query("$WITH_COUNT WHERE i.id = :id GROUP BY i.id")
    abstract fun observeImport(id: Long): Flow<ImportWithCount?>

    @Query("$WITH_COUNT WHERE i.id = :id GROUP BY i.id")
    abstract suspend fun getImport(id: Long): ImportWithCount?

    @Query("SELECT * FROM archive_import_songs WHERE import_id = :importId ORDER BY position")
    abstract fun songs(importId: Long): Flow<List<ArchiveImportSongEntity>>

    @Query("SELECT * FROM archive_import_songs WHERE import_id = :importId ORDER BY position")
    abstract suspend fun getSongs(importId: Long): List<ArchiveImportSongEntity>

    @Query("UPDATE archive_imports SET playlist_id = :playlistId WHERE id = :id")
    abstract suspend fun setPlaylist(id: Long, playlistId: Long?)

    /** Deletes the import; its songs go with it (foreign key cascade). */
    @Query("DELETE FROM archive_imports WHERE id = :id")
    abstract suspend fun delete(id: Long)
}
