package io.sonora.core.data.library.edit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.conversion.saf.SafUris
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.saf.SafPaths
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import javax.inject.Inject

/**
 * Write access to the SAF tree a song lives in. Library folders added before Sonora asked for write access hold a
 * read-only grant; [requestFor] says which folder to pick again, and [grant] keeps read and write access once the user
 * picked that same folder.
 */
class SafWriteAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
) {

    /** What to ask for before writing [documentUri]; `null` when it is writable or not in a tree at all. */
    suspend fun requestFor(documentUri: String): WriteAccessRequest? {
        val tree = SafUris.treeOf(documentUri) ?: return null
        if (canWrite(tree)) return null
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val name = dao.folder(tree.toString())?.displayName ?: SafPaths.displayPath(treeId) ?: treeId
        val start = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        return WriteAccessRequest(tree.toString(), name, start.toString())
    }

    /**
     * Keeps read and write access to [pickedTreeUri] when it is [request]'s folder. A different folder is not kept:
     * a grant on it does not cover the song's URI, which names its library folder's tree.
     */
    fun grant(request: WriteAccessRequest, pickedTreeUri: String): WriteGrant {
        val picked = SafUris.treeOf(pickedTreeUri) ?: return WriteGrant.WRONG_FOLDER
        if (picked != SafUris.treeOf(request.treeUri)) return WriteGrant.WRONG_FOLDER
        return try {
            context.contentResolver.takePersistableUriPermission(picked, READ_WRITE)
            if (canWrite(picked)) WriteGrant.GRANTED else WriteGrant.REFUSED
        } catch (_: SecurityException) {
            // The picker returned the folder without write access (storage that cannot be written).
            WriteGrant.REFUSED
        }
    }

    private fun canWrite(tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    private companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
