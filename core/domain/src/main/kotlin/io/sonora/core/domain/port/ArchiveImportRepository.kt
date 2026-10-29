package io.sonora.core.domain.port

import io.sonora.core.domain.archive.ArchiveImportEntry
import io.sonora.core.domain.archive.ImportedSong
import kotlinx.coroutines.flow.Flow

/** The archive import history: every finished import with the songs it brought, kept until it is removed. */
interface ArchiveImportRepository {

    /** Stores [entry] (its id and song count are ignored) with its [songs] in order; returns the new id. */
    suspend fun add(entry: ArchiveImportEntry, songs: List<ImportedSong>): Long

    /** Every import with its [ArchiveImportEntry.songCount], newest first. */
    fun imports(): Flow<List<ArchiveImportEntry>>

    fun import(id: Long): Flow<ArchiveImportEntry?>

    /** The import's songs in order. */
    fun songs(importId: Long): Flow<List<ImportedSong>>

    suspend fun get(id: Long): ArchiveImportEntry?

    suspend fun getSongs(importId: Long): List<ImportedSong>

    /** Notes the playlist the import recreated (`null`: none). */
    suspend fun setPlaylist(id: Long, playlistId: Long?)

    /** Forgets the import and its songs; the files themselves stay where they are. */
    suspend fun remove(id: Long)
}
