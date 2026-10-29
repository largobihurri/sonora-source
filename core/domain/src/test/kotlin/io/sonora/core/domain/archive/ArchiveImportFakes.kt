package io.sonora.core.domain.archive

import io.sonora.core.domain.port.ArchiveImportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** [ArchiveImportRepository] in memory, newest first like the Room query (`ORDER BY imported_at_ms DESC, id DESC`). */
internal class FakeArchiveImports : ArchiveImportRepository {
    private val entries = MutableStateFlow<List<Pair<ArchiveImportEntry, List<ImportedSong>>>>(emptyList())
    private var nextId = 1L

    override suspend fun add(entry: ArchiveImportEntry, songs: List<ImportedSong>): Long {
        val id = nextId++
        entries.value += entry.copy(id = id, songCount = songs.size) to songs
        return id
    }

    override fun imports(): Flow<List<ArchiveImportEntry>> = entries.map { all ->
        all.map { it.first }.sortedWith(compareByDescending<ArchiveImportEntry> { it.importedAtMs }.thenByDescending { it.id })
    }

    override fun import(id: Long): Flow<ArchiveImportEntry?> = entries.map { all -> all.firstOrNull { it.first.id == id }?.first }

    override fun songs(importId: Long): Flow<List<ImportedSong>> = entries.map { all -> all.firstOrNull { it.first.id == importId }?.second.orEmpty() }

    override suspend fun get(id: Long): ArchiveImportEntry? = entries.value.firstOrNull { it.first.id == id }?.first

    override suspend fun getSongs(importId: Long): List<ImportedSong> = entries.value.firstOrNull { it.first.id == importId }?.second.orEmpty()

    override suspend fun setPlaylist(id: Long, playlistId: Long?) {
        entries.value = entries.value.map { if (it.first.id == id) it.first.copy(playlistId = playlistId) to it.second else it }
    }

    override suspend fun remove(id: Long) {
        entries.value = entries.value.filterNot { it.first.id == id }
    }
}
