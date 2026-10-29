package io.sonora.core.domain.port

import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.flow.Flow

/** Browses the library by its real folder tree (the library folders and the directories below them). */
interface FolderBrowser {
    /** The library folders, the top of the tree, by name. Re-emits when the library changes. */
    fun roots(): Flow<List<FolderEntry>>

    /** [folder]'s subfolders and own tracks (see [FolderContents]). Re-emits when the library changes. */
    fun contents(folder: FolderRef): Flow<FolderContents>

    /** Every track in [folder] and all its subfolders, in play order (`FolderTree.playOrder`). */
    suspend fun tracksUnder(folder: FolderRef): List<Track>
}
