package io.sonora.core.data.conversion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.data.library.saf.SafPaths
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.OutputRootFolder
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The converter's output folder: a SAF tree the user picks once, with persisted read and write access, stored in the
 * settings DataStore. A folder whose access was revoked since reads as unset, so the UI asks for it again.
 *
 * Grants live outside the DataStore, so access is checked again on every read and after every [set]: picking the
 * same folder again after a revoke changes only the grant, never the stored values. Android announces no revokes;
 * a watcher sees one on its next collection, and a batch checks access when it starts.
 */
@Singleton
class DataStoreConversionOutputRoot @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : ConversionOutputRootStore {

    /** Bumped by [set], whose grant may be all that changed. */
    private val grants = MutableStateFlow(0)

    override val root: Flow<OutputRootFolder?> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .combine(grants) { prefs, _ -> prefs }
        .map { prefs ->
            val treeUri = prefs[TREE_URI] ?: return@map null
            OutputRootFolder(treeUri, prefs[DISPLAY_NAME] ?: treeUri).takeIf { hasWriteAccess(treeUri.toUri()) }
        }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    override suspend fun set(treeUri: String): OutputRootFolder = withContext(Dispatchers.IO) {
        val tree = treeUri.toUri()
        context.contentResolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val folder = OutputRootFolder(treeUri, displayName(tree))
        dataStore.edit {
            it[TREE_URI] = folder.treeUri
            it[DISPLAY_NAME] = folder.displayName
        }
        grants.update { it + 1 }
        folder
    }

    private fun hasWriteAccess(tree: Uri) =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** `Music/DJ` for a folder, the provider's name (e.g. the USB stick's label) for a volume root. */
    private fun displayName(tree: Uri): String {
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        SafPaths.displayPath(documentId)?.let { return it }
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val name = try {
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
        return name ?: documentId
    }

    private companion object {
        val TREE_URI = stringPreferencesKey("converter_output_tree_uri")
        val DISPLAY_NAME = stringPreferencesKey("converter_output_display_name")
    }
}
