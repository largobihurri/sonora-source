package io.sonora.feature.converter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.sonora.core.domain.model.PickedSource
import io.sonora.feature.converter.PendingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The converter's system pickers; each launch returns false when the device has no picker app. */
internal class ConverterPickers(
    val addFiles: () -> Boolean,
    val addFolder: () -> Boolean,
    val chooseOutputFolder: () -> Boolean,
)

/**
 * Pickers for sources (several audio documents, or one folder tree) and the output folder. Picked sources reach
 * [onSources] with their display names, read off the main thread.
 */
@Composable
internal fun rememberConverterPickers(onSources: (List<PendingSource>) -> Unit, onOutputFolder: (String) -> Unit): ConverterPickers {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val sources by rememberUpdatedState(onSources)
    val output by rememberUpdatedState(onOutputFolder)
    fun deliver(resolve: () -> List<PendingSource>) {
        scope.launch { sources(withContext(Dispatchers.IO) { resolve() }) }
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            deliver { uris.map { PendingSource(PickedSource.File(it.toString()), SourceNames.document(context, it)) } }
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { deliver { listOf(PendingSource(PickedSource.Folder(it.toString()), SourceNames.tree(context, it))) } }
    }
    val outputFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { output(it.toString()) }
    }
    return remember(files, folder, outputFolder) {
        ConverterPickers(
            addFiles = { launching { files.launch(arrayOf(AUDIO_MIME)) } },
            addFolder = { launching { folder.launch(null) } },
            chooseOutputFolder = { launching { outputFolder.launch(null) } },
        )
    }
}

private const val AUDIO_MIME = "audio/*"

private inline fun launching(launch: () -> Unit): Boolean = try {
    launch()
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Display names of picked documents, falling back to the URI's last path segment. */
internal object SourceNames {

    fun document(context: Context, uri: Uri): String =
        query(context, uri) ?: runCatching { fallback(DocumentsContract.getDocumentId(uri)) }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    fun tree(context: Context, tree: Uri): String = treeName(
        documentId = { DocumentsContract.getTreeDocumentId(tree) },
        displayName = { id -> query(context, DocumentsContract.buildDocumentUriUsingTree(tree, id)) },
        uriFallback = tree.lastPathSegment.orEmpty(),
    )

    /**
     * A picked tree's name: the provider's, else its document id's last segment. A result that is no tree URI (some
     * pickers return a plain document) has no document id; its [uriFallback] names it instead of crashing.
     */
    fun treeName(documentId: () -> String, displayName: (String) -> String?, uriFallback: String): String {
        val id = try {
            documentId()
        } catch (_: IllegalArgumentException) {
            return uriFallback
        }
        return displayName(id) ?: fallback(id)
    }

    private fun query(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null // revoked or a provider without names
    }

    /** "primary:Music/DJ" -> "DJ"; "primary:" -> "primary". */
    private fun fallback(documentId: String): String =
        documentId.substringAfter(':').substringAfterLast('/').ifBlank { documentId.substringBefore(':') }
}
