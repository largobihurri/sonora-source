package io.sonora.core.data.library

import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.domain.library.FolderTree
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [FolderBrowser] over the scanner's index: library folders are the roots and every stored track path names a
 * directory (see [FolderTree]). Queries stay inside one library folder, so same-named directories of different
 * library folders never mix. Mapping and ordering run off the main thread.
 *
 * A root is the library folder's tree under its current name, and its count is every track of that tree. Stored paths
 * start with the name the folder had when it was scanned, so they are matched below that first segment and shown
 * re-rooted at the current name: the count, the folder screens and Play always cover the same tracks.
 */
@Singleton
class RoomFolderBrowser @Inject constructor(
    private val dao: LibraryDao,
) : FolderBrowser {

    override fun roots(): Flow<List<FolderEntry>> = dao.folderRoots()
        .map { rows -> rows.map { FolderEntry(FolderRef(it.treeUri, it.displayName), it.trackCount) } }
        .flowOn(Dispatchers.Default)

    override fun contents(folder: FolderRef): Flow<FolderContents> =
        combine(
            dao.folderTrackCounts(folder.treeUri, folder.path),
            dao.tracksInFolder(folder.treeUri, folder.path),
        ) { counts, own ->
            val byPath = HashMap<String, Int>()
            for (row in counts) byPath.merge(folder.reroot(row.folderPath), row.trackCount, Int::plus)
            FolderTree.contents(folder, byPath, own.map { it.toDomain(folder) })
        }.flowOn(Dispatchers.Default)

    override suspend fun tracksUnder(folder: FolderRef): List<Track> {
        val rows = dao.tracksUnderFolder(folder.treeUri, folder.path)
        return withContext(Dispatchers.Default) { FolderTree.playOrder(rows.map { it.toDomain(folder) }) }
    }

    private fun TrackEntity.toDomain(folder: FolderRef): Track = toDomain().copy(folderPath = folder.reroot(folderPath))

    /** A stored [path] of this folder's tree with its first segment replaced by the library folder's current name. */
    private fun FolderRef.reroot(path: String): String {
        val root = this.path.substringBefore('/')
        val slash = path.indexOf('/')
        return if (slash < 0) root else root + path.substring(slash)
    }
}
