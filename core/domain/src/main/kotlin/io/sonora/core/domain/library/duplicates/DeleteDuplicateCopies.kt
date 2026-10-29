package io.sonora.core.domain.library.duplicates

import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.DeleteResult
import io.sonora.core.domain.port.WriteAccessRequest

/** What deleting copies of a duplicate group did. */
sealed interface DuplicateDeletion {
    /** [deleted] copies are gone, [failed] are still there; [cleanUpFailed] as for "Delete from Phone". */
    data class Deleted(val deleted: Int, val failed: Int, val cleanUpFailed: Boolean = false) : DuplicateDeletion

    /** A folder is held read-only: nothing was deleted; pick it again to allow it, then retry. */
    data class NeedsWriteAccess(val request: WriteAccessRequest) : DuplicateDeletion

    /** Every copy was selected: refused, the group's last copy is never deleted. */
    data object KeepsNoCopy : DuplicateDeletion

    data object NothingSelected : DuplicateDeletion
}

/**
 * Deletes the [selected] copies of a duplicate group from the phone, through "Delete from Phone" (write access checked
 * for every file first). At least one copy always stays: the best one not selected, and every playlist entry of a
 * deleted copy moves to it, in place, with the copy's favourite, pins and play count (summed, the latest time played
 * kept); hidden marks are dropped. A copy the storage refuses keeps its entries and marks.
 */
class DeleteDuplicateCopies(private val deleteFromPhone: DeleteFromPhone) {
    suspend operator fun invoke(group: DuplicateGroup, selected: Set<String>): DuplicateDeletion {
        val doomed = group.copies.filter { it.uri in selected }.map { it.uri }
        if (doomed.isEmpty()) return DuplicateDeletion.NothingSelected
        val kept = group.copies.firstOrNull { it.uri !in selected } ?: return DuplicateDeletion.KeepsNoCopy
        return when (val result = deleteFromPhone.uris(doomed, replacements = doomed.associateWith { kept.uri })) {
            is DeleteResult.Deleted -> DuplicateDeletion.Deleted(result.deleted, result.failed, result.cleanUpFailed)
            is DeleteResult.NeedsWriteAccess -> DuplicateDeletion.NeedsWriteAccess(result.request)
            DeleteResult.NothingToDelete -> DuplicateDeletion.NothingSelected
        }
    }
}
