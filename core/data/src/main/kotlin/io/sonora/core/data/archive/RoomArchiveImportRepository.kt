package io.sonora.core.data.archive

import io.sonora.core.data.archive.db.ArchiveImportDao
import io.sonora.core.data.archive.db.ArchiveImportEntity
import io.sonora.core.data.archive.db.ArchiveImportSongEntity
import io.sonora.core.data.archive.db.ImportWithCount
import io.sonora.core.domain.archive.ArchiveImportEntry
import io.sonora.core.domain.archive.ImportedSong
import io.sonora.core.domain.port.ArchiveImportRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [ArchiveImportRepository] in the app's Room database (tables `archive_imports` and `archive_import_songs`). */
class RoomArchiveImportRepository @Inject constructor(private val dao: ArchiveImportDao) : ArchiveImportRepository {

    override suspend fun add(entry: ArchiveImportEntry, songs: List<ImportedSong>): Long = dao.insert(
        ArchiveImportEntity.of(entry),
        songs.map { ArchiveImportSongEntity(importId = 0, position = 0, documentKey = it.documentKey, title = it.title, artist = it.artist) },
    )

    override fun imports(): Flow<List<ArchiveImportEntry>> = dao.imports().map { rows -> rows.map(ImportWithCount::toDomain) }

    override fun import(id: Long): Flow<ArchiveImportEntry?> = dao.observeImport(id).map { it?.toDomain() }

    override fun songs(importId: Long): Flow<List<ImportedSong>> = dao.songs(importId).map { rows -> rows.map(ArchiveImportSongEntity::toDomain) }

    override suspend fun get(id: Long): ArchiveImportEntry? = dao.getImport(id)?.toDomain()

    override suspend fun getSongs(importId: Long): List<ImportedSong> = dao.getSongs(importId).map(ArchiveImportSongEntity::toDomain)

    override suspend fun setPlaylist(id: Long, playlistId: Long?) = dao.setPlaylist(id, playlistId)

    override suspend fun remove(id: Long) = dao.delete(id)
}
