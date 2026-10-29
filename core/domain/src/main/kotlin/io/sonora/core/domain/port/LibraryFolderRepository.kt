package io.sonora.core.domain.port

import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.LibraryFolder
import kotlinx.coroutines.flow.Flow

/** The folders that make up the library. Only these are scanned. */
interface LibraryFolderRepository {
    fun folders(): Flow<List<LibraryFolder>>

    /**
     * Adds [treeUri] (a tree picked with ACTION_OPEN_DOCUMENT_TREE) and keeps read access to it across restarts,
     * plus write access where the storage allows it (the tag editor saves into the folder's files).
     * Folders never overlap: the same folder, one inside an added folder, or one containing an added folder is
     * [AddFolderResult.Rejected] without keeping any access. All or nothing: when adding fails, no access grant is
     * kept. The scanner picks up the new folder by itself ([LibraryScanner]).
     */
    suspend fun add(treeUri: String): AddFolderResult

    /** Drops the folder and its indexed tracks immediately and releases the access grant. */
    suspend fun remove(treeUri: String)
}
