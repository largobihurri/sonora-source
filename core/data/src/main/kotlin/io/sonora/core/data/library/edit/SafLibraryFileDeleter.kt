package io.sonora.core.data.library.edit

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.conversion.saf.SafUris
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [LibraryFileDeleter] over SAF documents: the song's tree must be held with write access ([SafWriteAccess], the
 * same re-grant as the tag editor); the document is deleted through its provider and its row leaves the index at once,
 * without a rescan. A song whose file is already gone counts as deleted, so its row does not linger until a rescan.
 */
class SafLibraryFileDeleter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
    private val writeAccess: SafWriteAccess,
) : LibraryFileDeleter {

    override suspend fun writeAccessFor(uri: String): WriteAccessRequest? = writeAccess.requestFor(uri)

    override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant =
        withContext(Dispatchers.IO) { writeAccess.grant(request, pickedTreeUri) }

    override suspend fun delete(uri: String): Boolean {
        val document = uri.toUri()
        val deleted = withContext(Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, document) || isGone(document)
            } catch (_: FileNotFoundException) {
                isGone(document)
            } catch (e: SecurityException) {
                Log.w(TAG, "Deleting a song was refused", e)
                isGone(document)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Deleting a song is not supported here", e)
                isGone(document)
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "Deleting a song is not supported here", e)
                isGone(document)
            }
        }
        // The file is gone for good: its row goes too, even when the caller stops waiting, and a database failure
        // does not turn the deletion into a refusal (the next scan drops the row then).
        if (deleted) {
            withContext(NonCancellable) {
                try {
                    dao.deleteTracks(listOf(uri))
                } catch (e: Exception) {
                    Log.w(TAG, "A deleted song stays listed until the next scan", e)
                }
            }
        }
        return deleted
    }

    /**
     * Whether [document] no longer exists while its folder tree still does: a song deleted outside Sonora since the
     * last scan. A provider that cannot reach the tree at all (an unmounted card, a revoked grant) says nothing about
     * the file, so it is not taken for gone.
     */
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
        const val TAG = "SonoraDelete"
    }
}
