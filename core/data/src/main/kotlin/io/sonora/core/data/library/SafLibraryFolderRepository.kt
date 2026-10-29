package io.sonora.core.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.saf.SafPaths
import io.sonora.core.domain.library.LibraryFolderRules
import io.sonora.core.domain.library.removal.FolderAccessUsers
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.port.LibraryFolderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [LibraryFolderRepository] over persisted SAF tree grants and the Room index. */
@Singleton
class SafLibraryFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: LibraryDao,
    private val otherUsers: FolderAccessUsers,
) : LibraryFolderRepository {

    override fun folders(): Flow<List<LibraryFolder>> =
        dao.folders().map { rows -> rows.map(LibraryFolderEntity::toDomain) }

    private val addLock = Mutex()

    /**
     * All or nothing: the name lookup and the overlap check run before the grant is taken, and the grant is
     * released again if storing the folder fails, unless it was already held before this add. Throws
     * [IllegalArgumentException] for a URI that is not a tree and [SecurityException] when [treeUri] was not
     * granted with a persistable read permission.
     */
    override suspend fun add(treeUri: String): AddFolderResult = withContext(Dispatchers.IO) {
        addLock.withLock {
            val tree = treeUri.toUri()
            val name = displayName(tree)
            val conflict = LibraryFolderRules.conflict(treeUri, dao.folderList().map(LibraryFolderEntity::toDomain))
            if (conflict != null) return@withLock AddFolderResult.Rejected(conflict)
            val resolver = context.contentResolver
            val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
            takeGrant(tree)
            try {
                dao.insertFolder(LibraryFolderEntity(treeUri, name, System.currentTimeMillis()))
                AddFolderResult.Added(checkNotNull(dao.folder(treeUri)) { "Folder $treeUri was not stored" }.toDomain())
            } catch (e: Throwable) {
                // A grant held before this add belongs to whoever took it.
                if (!alreadyGranted) releaseGrant(treeUri)
                throw e
            }
        }
    }

    /**
     * Android keeps one grant per tree, shared with whatever else took it (the converter's output folder or sources,
     * picked as the same folder), so the grant is released only when [otherUsers] no longer need it; when they cannot
     * be asked it is kept, since a grant left behind costs nothing and a lost one breaks the converter.
     */
    override suspend fun remove(treeUri: String) = withContext(Dispatchers.IO) {
        dao.removeFolder(treeUri)
        if (!neededElsewhere(treeUri)) releaseGrant(treeUri)
    }

    private suspend fun neededElsewhere(treeUri: String): Boolean = try {
        otherUsers.needs(treeUri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot tell whether a removed folder's access is still used; keeping it", e)
        true
    }

    /**
     * Read and write access, so the tag editor can save into the folder's files; read-only when the folder cannot be
     * written (reading is all the library needs). A read refusal propagates.
     */
    private fun takeGrant(tree: Uri) {
        val resolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(tree, READ_WRITE)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun releaseGrant(treeUri: String) {
        try {
            // Releasing a mode that was never held is fine; the grant goes once no mode is left.
            context.contentResolver.releasePersistableUriPermission(treeUri.toUri(), READ_WRITE)
        } catch (_: SecurityException) {
            // The grant is already gone (revoked or never persisted).
        }
    }

    private companion object {
        const val TAG = "SonoraFolders"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    /** `Music/Rock` for a folder, the provider's name (e.g. the SD card label) for a volume root. */
    private fun displayName(tree: Uri): String {
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        return SafPaths.displayPath(documentId) ?: queryDisplayName(tree, documentId) ?: documentId
    }

    private fun queryDisplayName(tree: Uri, documentId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
