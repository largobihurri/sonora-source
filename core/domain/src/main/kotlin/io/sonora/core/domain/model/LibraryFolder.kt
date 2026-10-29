package io.sonora.core.domain.model

/** A folder the user added to the library: a persisted Storage Access Framework tree. */
data class LibraryFolder(
    val treeUri: String,
    /** Human-readable path such as `Music/Rock`; also the root of every [Track.folderPath] inside it. */
    val displayName: String,
    val addedAtMs: Long,
)

/** Why a folder cannot be added: library folders never overlap, so no file is indexed twice. */
sealed interface FolderConflict {
    val existing: LibraryFolder

    data class AlreadyAdded(override val existing: LibraryFolder) : FolderConflict

    /** The picked folder lies inside [existing], which already covers it. */
    data class InsideExisting(override val existing: LibraryFolder) : FolderConflict

    /** The picked folder contains [existing]; that one has to be removed first. */
    data class ContainsExisting(override val existing: LibraryFolder) : FolderConflict
}

sealed interface AddFolderResult {
    data class Added(val folder: LibraryFolder) : AddFolderResult

    data class Rejected(val conflict: FolderConflict) : AddFolderResult
}
