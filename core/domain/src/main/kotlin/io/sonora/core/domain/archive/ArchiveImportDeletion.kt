package io.sonora.core.domain.archive

import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.DeleteResult
import io.sonora.core.domain.port.ArchiveImportRepository
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.WriteAccessRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Deletes a folder an import extracted, with everything in it. Main-safe. */
fun interface ImportedFolderDeleter {
    /**
     * Deletes the folder [folderUri] (a document reached through the import's destination tree) and all its files,
     * reached that way or through one of [trees] (tree URIs holding it, such as library folders), whichever Sonora
     * may still write; `true` once it is gone.
     */
    suspend fun delete(folderUri: String, trees: List<String>): Boolean
}

/** The URIs of every song file the library indexes, hidden songs included. */
fun interface IndexedSongUris {
    suspend fun all(): List<String>
}

/** What "Delete from Phone" did to an import. */
sealed interface ImportDeletion {
    /** A library folder holding the songs is read-only: nothing was deleted; pick it again to allow it, then retry. */
    data class NeedsWriteAccess(val request: WriteAccessRequest) : ImportDeletion

    /** The import's [songs] (those in the library), its folder and its playlist are gone, and so is its record. */
    data class Deleted(val songs: Int) : ImportDeletion

    /**
     * Something is still on the phone: [deleted] songs went, [failed] could not be deleted, and the folder was kept
     * ([folderKept]). The record stays, so it can be tried again.
     */
    data class Incomplete(val deleted: Int, val failed: Int, val folderKept: Boolean) : ImportDeletion

    /** The import is no longer in the history. */
    data object Gone : ImportDeletion
}

/** "Delete from Phone" for an import of the history. */
interface ImportDeleter {
    suspend fun delete(importId: Long): ImportDeletion
}

/**
 * [ImportDeleter]: "Delete from Phone" for an import of the history. Its songs are found in the library by document
 * ([ArchiveImportRules.documentKey]): the library reaches them through its own folder's tree, not the destination's.
 * They are deleted with [DeleteFromPhone] (write access checked first, then the files, and the songs leave every
 * playlist and lose their favourites, pins and hidden marks). Only when every song went is the rest deleted: the
 * recreated playlist, the extracted folder with whatever else it holds (through the destination or a library folder
 * holding it), then the library folder that is the extracted folder itself (its grant released unless something else
 * uses it), and last the record. Anything still on the phone keeps the record, to try again.
 */
class DeleteArchiveImport(
    private val imports: ArchiveImportRepository,
    private val indexed: IndexedSongUris,
    private val deleteFromPhone: DeleteFromPhone,
    private val libraryFolders: LibraryFolderRepository,
    private val folders: ImportedFolderDeleter,
    private val playlists: PlaylistRepository,
) : ImportDeleter {
    override suspend fun delete(importId: Long): ImportDeletion = invoke(importId)

    suspend operator fun invoke(importId: Long): ImportDeletion {
        val entry = imports.get(importId) ?: return ImportDeletion.Gone
        val keys = imports.getSongs(importId).mapTo(HashSet()) { it.documentKey }
        val uris = indexed.all().filter { ArchiveImportRules.documentKey(it) in keys }
        val songs = when (val deleted = deleteFromPhone.uris(uris)) {
            is DeleteResult.NeedsWriteAccess -> return ImportDeletion.NeedsWriteAccess(deleted.request)
            DeleteResult.NothingToDelete -> DeleteResult.Deleted(deleted = 0, failed = 0)
            is DeleteResult.Deleted -> deleted
        }
        if (songs.failed > 0) return ImportDeletion.Incomplete(songs.deleted, songs.failed, folderKept = true)
        // Songs are gone for good: what is left of the import goes too, even when the caller stops waiting.
        return withContext(NonCancellable) {
            entry.playlistId?.let { id -> quietly { playlists.delete(id) } }
            val holding = ArchiveImportRules.foldersHolding(entry.folderUri, libraryFolders.folders().first())
            val folderGone = quietly { folders.delete(entry.folderUri, holding.map { it.treeUri }) } == true
            if (!folderGone) return@withContext ImportDeletion.Incomplete(songs.deleted, failed = 0, folderKept = true)
            for (own in holding.filter { ArchiveImportRules.isExtractedFolder(it.treeUri, entry.folderUri) }) {
                quietly { libraryFolders.remove(own.treeUri) }
            }
            imports.remove(importId)
            ImportDeletion.Deleted(songs.deleted)
        }
    }

    /**
     * One clean-up step; `null` when it failed, so the next steps still run. It runs under [NonCancellable], so a
     * [kotlin.coroutines.cancellation.CancellationException] here comes from the step itself and is one more failure.
     */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }
}
