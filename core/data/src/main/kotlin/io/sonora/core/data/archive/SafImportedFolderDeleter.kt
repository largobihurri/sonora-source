package io.sonora.core.data.archive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.conversion.saf.SafUris
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.domain.archive.ImportedFolderDeleter
import io.sonora.core.domain.archive.IndexedSongUris
import io.sonora.core.domain.log.redactForLog
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ImportedFolderDeleter] over SAF: the folder is deleted through its provider (external storage deletes a folder with
 * its contents), first as the import reached it, under the destination's grant, then through each tree given, under
 * that tree's grant (a library folder's, which may outlive the destination's). A folder already gone counts as
 * deleted, but only when its tree is still reachable: an unmounted card or a revoked grant says nothing about it.
 */
class SafImportedFolderDeleter @Inject constructor(@ApplicationContext private val context: Context) : ImportedFolderDeleter {

    override suspend fun delete(folderUri: String, trees: List<String>): Boolean = withContext(Dispatchers.IO) {
        val folder = folderUri.toUri()
        val documentId = try {
            DocumentsContract.getDocumentId(folder)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Not a document: ${redactForLog(folderUri)}", e)
            return@withContext false
        }
        val candidates = listOf(folder) + trees.mapNotNull { tree ->
            try {
                DocumentsContract.buildDocumentUriUsingTree(tree.toUri(), documentId)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        candidates.distinct().any(::deleteOne)
    }

    private fun deleteOne(document: Uri): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, document) || isGone(document)
    } catch (_: FileNotFoundException) {
        isGone(document)
    } catch (e: SecurityException) {
        Log.w(TAG, "Deleting an imported folder was refused through ${document.authority}", e)
        false
    } catch (e: RuntimeException) {
        // IllegalArgumentException, UnsupportedOperationException or a provider's own failure.
        Log.w(TAG, "Cannot delete an imported folder", e)
        false
    }

    /** Whether [document] no longer exists while the tree it was reached through still does. */
    private fun isGone(document: Uri): Boolean {
        val tree = SafUris.treeOf(document.toString()) ?: return false
        return exists(SafUris.folderDocument(tree.toString())) == true && exists(document) == false
    }

    /** `null` when the provider could not be asked. A missing document reads as no cursor (DocumentsProvider). */
    private fun exists(document: Uri): Boolean? = try {
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TAG = "SonoraImportHistory"
    }
}

/** [IndexedSongUris] from the library index. */
class RoomIndexedSongUris @Inject constructor(private val dao: LibraryDao) : IndexedSongUris {
    override suspend fun all(): List<String> = dao.allTrackUris()
}
