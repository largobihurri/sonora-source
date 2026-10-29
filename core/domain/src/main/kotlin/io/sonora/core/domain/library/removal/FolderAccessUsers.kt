package io.sonora.core.domain.library.removal

import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionOutputRootStore
import kotlinx.coroutines.flow.first

/**
 * Features besides the library that rely on Sonora's persisted access to a folder tree. Android keeps one grant per
 * tree, shared by everything that took it, so removing a library folder releases its grant only when nothing here
 * still needs it.
 */
fun interface FolderAccessUsers {
    /** Whether something besides the library still reads or writes inside [treeUri]. */
    suspend fun needs(treeUri: String): Boolean
}

/**
 * The converter's hold on a tree: its output folder, and every batch in its history with a file there (a source, the
 * folder it sits in, the batch folder or a converted file), which "Converted" lists and "Replace originals" may still
 * write next to. A batch keeps its tree until it is deleted from the history.
 */
class ConverterFolderAccess(
    private val outputRoot: ConversionOutputRootStore,
    private val batches: ConversionBatchRepository,
) : FolderAccessUsers {

    override suspend fun needs(treeUri: String): Boolean {
        if (outputRoot.root.first()?.treeUri?.let { inTree(it, treeUri) } == true) return true
        return batches.batches().first().any { batch ->
            batch.outputFolderUri?.let { inTree(it, treeUri) } == true ||
                batches.getItems(batch.id).any { item ->
                    listOfNotNull(item.sourceUri, item.sourceParentUri, item.outputUri).any { inTree(it, treeUri) }
                }
        }
    }

    /** The tree itself or a document reached through it (`<tree>/document/<id>`). */
    private fun inTree(uri: String, treeUri: String): Boolean = uri == treeUri || uri.startsWith("$treeUri/")
}
