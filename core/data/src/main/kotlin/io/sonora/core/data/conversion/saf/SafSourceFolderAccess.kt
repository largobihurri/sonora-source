package io.sonora.core.data.conversion.saf

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.port.SourceFolderAccess
import javax.inject.Inject

/**
 * Write access to picked source folders, persisted so originals can be replaced after the batch, even after the app
 * restarted. The folder picker (ACTION_OPEN_DOCUMENT_TREE) grants persistable read and write access.
 */
class SafSourceFolderAccess @Inject constructor(@ApplicationContext private val context: Context) : SourceFolderAccess {

    override suspend fun persistWrite(treeUri: String): Boolean {
        val resolver = context.contentResolver
        val tree = treeUri.toUri()
        return try {
            resolver.takePersistableUriPermission(tree, READ_WRITE)
            true
        } catch (_: SecurityException) {
            // Read-only grant: keep reading (a restarted batch needs it); replacing originals will say why it cannot.
            try {
                resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Not persistable at all: the batch reads it while the grant lasts.
            }
            false
        }
    }

    override suspend fun canWrite(folderUri: String): Boolean {
        val tree = SafUris.treeOf(folderUri) ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
    }

    private companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
