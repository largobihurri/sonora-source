package io.sonora.core.domain.port

/** Write access to the folders the sources come from, which replacing originals needs. */
interface SourceFolderAccess {

    /** Persists read and write access to a picked folder [treeUri]; false when only read (or nothing) was granted. */
    suspend fun persistWrite(treeUri: String): Boolean

    /** Whether the app holds persisted write access to the tree containing [folderUri]. */
    suspend fun canWrite(folderUri: String): Boolean
}
