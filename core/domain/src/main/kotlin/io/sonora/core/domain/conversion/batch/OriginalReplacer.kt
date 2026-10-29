package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.SourceFolderAccess
import io.sonora.core.domain.port.StoredDocument
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Replaces an original with its converted file. Shared storage has no trash, so the original is deleted only once
 * a copy of the verified output sits next to it and decodes too:
 * 1. copy the output from the batch folder next to the original, as "Name.aiff" (numbered if the name is taken);
 * 2. verify that copy decodes to what the conversion produced;
 * 3. delete the original (one found already gone, after an interrupted replacement, counts as deleted);
 * 4. for an AIFF original, whose name the copy could not take while it existed, rename the copy to that name;
 * 5. record the replacement in the history;
 * 6. delete the batch folder's copy, so the file moved rather than duplicated.
 *
 * Any failure up to step 3 removes the copy and leaves the original and the batch output untouched. Steps 3 to 6
 * finish even when the caller is cancelled and never throw: until the history records the copy next to the
 * original it records the batch copy, which therefore stays (at worst as a duplicate).
 */
class OriginalReplacer(
    private val files: ConversionFiles,
    private val access: SourceFolderAccess,
    private val converter: AudioConverter,
    private val repository: ConversionBatchRepository,
) {

    /**
     * [item] (which must have [ConversionBatchItem.canReplaceOriginal]) as replaced, already recorded in the history,
     * or as failed with a reason and its output still in the batch folder, for the caller to record.
     */
    suspend fun replace(profile: ConversionProfile, item: ConversionBatchItem): ConversionBatchItem {
        val output = checkNotNull(item.outputUri) { "Item ${item.id} has no output" }
        val result = checkNotNull(item.result) { "Item ${item.id} has no conversion result" }
        val folder = item.sourceParentUri ?: return item.failed(NO_FOLDER)

        // The original's own folder accepted its name, so no FAT clean-up here beyond the length the new extension
        // may add: keep the name as close as possible.
        val desired = BatchNaming.fit(profile.outputFileName(item.sourceName))
        var copy: StoredDocument? = null
        try {
            if (!access.canWrite(folder)) return item.failed(NO_WRITE_ACCESS)
            val name = BatchNaming.uniqueFileName(desired, files.children(folder).map { it.name })
            copy = files.createFile(folder, name, profile.container.mimeType)
            files.copy(output, copy.uri)
            val verification = converter.verify(copy.uri, result)
            if (verification is OutputVerification.Invalid) {
                files.delete(copy.uri)
                return item.failed("The copy next to the original does not decode (${verification.reason}); the original was kept")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { copy?.let { files.delete(it.uri) } }
            throw e
        } catch (e: Exception) {
            copy?.let { files.delete(it.uri) }
            return item.failed("Could not write next to the original (${e.message ?: e.javaClass.simpleName}); the original was kept")
        }

        val sibling: StoredDocument = checkNotNull(copy)
        // Past this point the original goes away: finish even if the batch is being cancelled.
        return withContext(NonCancellable) { finish(item, folder, output, sibling, desired) }
    }

    private suspend fun finish(
        item: ConversionBatchItem,
        folder: String,
        output: String,
        sibling: StoredDocument,
        desired: String,
    ): ConversionBatchItem {
        if (!deleteOriginal(item, folder, sibling)) {
            delete(sibling.uri)
            return item.failed("The original could not be deleted, so it was kept")
        }
        var final = sibling
        if (sibling.name != desired && desired.equals(item.sourceName, ignoreCase = true)) {
            final = try {
                files.rename(sibling.uri, desired) ?: sibling
            } catch (_: Exception) {
                locate(folder, sibling, desired)
            }
        }
        val replaced = item.copy(
            state = BatchItemState.REPLACED_ORIGINAL,
            outputUri = final.uri,
            outputName = final.name,
            failureReason = null,
            suffixedName = final.name != desired,
        )
        val recorded = try {
            repository.updateItem(replaced)
            true
        } catch (_: Exception) {
            false
        }
        // A leftover would only be a duplicate in the batch folder; while unrecorded it is the one the history knows.
        if (recorded) delete(output)
        return replaced
    }

    /**
     * Deletes the batch folder [folderUri] once replacing originals emptied it; a folder still holding anything (an
     * output whose original was kept, a file the user put there) or that cannot be listed stays. Never throws.
     */
    suspend fun removeIfEmpty(folderUri: String) {
        val empty = try {
            files.children(folderUri).isEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (empty) delete(folderUri)
    }

    /**
     * Deletes the original; true once it is gone, also when the storage reports a failure but the original is no
     * longer listed (or was already deleted by an interrupted replacement). When unsure, it is still there.
     */
    private suspend fun deleteOriginal(item: ConversionBatchItem, folder: String, sibling: StoredDocument): Boolean {
        if (delete(item.sourceUri)) return true
        return try {
            files.children(folder).none { it.uri != sibling.uri && (it.uri == item.sourceUri || it.name == item.sourceName) }
        } catch (_: Exception) {
            false
        }
    }

    /** Where [sibling] is after a rename that threw: renamed to [desired] or not, as the folder lists it now. */
    private suspend fun locate(folder: String, sibling: StoredDocument, desired: String): StoredDocument = try {
        val listed = files.children(folder)
        listed.firstOrNull { it.uri == sibling.uri } ?: listed.firstOrNull { it.name == desired } ?: sibling
    } catch (_: Exception) {
        sibling
    }

    /** [ConversionFiles.delete], which must not throw, guarded anyway: an original may already be gone. */
    private suspend fun delete(uri: String): Boolean = try {
        files.delete(uri)
    } catch (_: Exception) {
        false
    }

    private fun ConversionBatchItem.failed(reason: String) = copy(state = BatchItemState.FAILED, failureReason = reason)

    companion object {
        const val NO_FOLDER =
            "The original's folder is unknown because the file was picked on its own; pick its folder to replace it"
        const val NO_WRITE_ACCESS =
            "No write access to the original's folder; pick the folder again and allow access to replace it"
    }
}
