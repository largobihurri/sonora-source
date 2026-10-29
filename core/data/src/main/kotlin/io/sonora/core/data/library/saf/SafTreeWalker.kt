package io.sonora.core.data.library.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.library.FileIdentity
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One audio document found under a library folder. */
data class ListedAudioFile(
    val documentUri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    /** Containing directory, starting with the library folder's display path. */
    val folderPath: String,
    /** Document URI of the directory's best cover image ([FolderImages.pick]), if any. */
    val folderImageUri: String?,
) {
    val identity get() = FileIdentity(documentUri, sizeBytes, lastModifiedMs)
}

/** Lists the audio files of one library folder. */
fun interface LibraryWalker {
    /**
     * Lists every audio file under [treeUri]; [rootPath] names the tree's root in [ListedAudioFile.folderPath].
     * Throws when the tree cannot be listed (revoked grant, missing volume), so callers can keep the folder's
     * previous index.
     */
    suspend fun walk(treeUri: String, rootPath: String): List<ListedAudioFile>
}

/**
 * [LibraryWalker] over a persisted SAF tree with DocumentsContract child queries (no DocumentFile per-row lookups).
 * Hidden entries (dot files, including macOS `._` resource forks) are skipped.
 */
class SafTreeWalker @Inject constructor(
    @ApplicationContext private val context: Context,
) : LibraryWalker {
    override suspend fun walk(treeUri: String, rootPath: String): List<ListedAudioFile> {
        val tree = treeUri.toUri()
        val resolver = context.contentResolver
        val files = mutableListOf<ListedAudioFile>()
        val pending = mutableListOf(DocumentsContract.getTreeDocumentId(tree) to rootPath)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (directoryId, directoryPath) = pending.removeAt(pending.lastIndex)
            val children = children(resolver, tree, directoryId).filterNot { it.name.startsWith('.') }
            val coverName = FolderImages.pick(children.filterNot { it.isDirectory }.map { it.name })
            val coverUri = children.firstOrNull { it.name == coverName && !it.isDirectory }
                ?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.documentId).toString() }
            for (child in children) {
                when {
                    child.isDirectory -> pending += child.documentId to "$directoryPath/${child.name}"
                    AudioFiles.isAudio(child.name, child.mimeType) -> files += ListedAudioFile(
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId).toString(),
                        fileName = child.name,
                        mimeType = child.mimeType ?: "audio/*",
                        sizeBytes = child.sizeBytes,
                        lastModifiedMs = child.lastModifiedMs,
                        folderPath = directoryPath,
                        folderImageUri = coverUri,
                    )
                }
            }
        }
        return files
    }

    private fun children(resolver: ContentResolver, tree: Uri, directoryId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: error("No listing for $uri")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    add(
                        Child(
                            documentId = it.getString(0),
                            name = name,
                            mimeType = it.getString(2),
                            sizeBytes = if (it.isNull(3)) 0L else it.getLong(3),
                            lastModifiedMs = if (it.isNull(4)) 0L else it.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    ) {
        val isDirectory get() = mimeType == Document.MIME_TYPE_DIR
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
