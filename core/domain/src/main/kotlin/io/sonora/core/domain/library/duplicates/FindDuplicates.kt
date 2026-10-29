package io.sonora.core.domain.library.duplicates

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Finds the library's duplicate groups ([DuplicateGrouping]), less those the user marked "Not a duplicate".
 *
 * Incremental: only files new or changed since the last scan are looked at again, and of those only the ones whose
 * size another file shares are read, one at a time ([ContentHasher] streams them). What was learnt is stored even when
 * the scan is stopped halfway, so the next one goes on from there. A file that cannot be read is not linked by its
 * content and is tried again next time, stored as [DuplicateFingerprint.UNREADABLE] so it does not count as not checked.
 */
class FindDuplicates(
    private val library: DuplicateLibrary,
    private val hasher: ContentHasher,
    private val fingerprints: DuplicateFingerprints,
    private val dismissals: DuplicateDismissals,
) {
    /** [onProgress] gets how many of the files not checked yet are done, of how many. */
    suspend operator fun invoke(onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }): List<DuplicateGroup> {
        val copies = library.copies()
        val stored = fingerprints.all()
        val plan = DuplicateFingerprinting.plan(copies, stored)
        val total = plan.work
        var checked = total - plan.toHash.size
        onProgress(checked, total)
        val hashes = HashMap(plan.hashes)
        val unreadable = HashSet<String>()
        try {
            for (file in plan.toHash) {
                currentCoroutineContext().ensureActive()
                val hash = hashQuietly(file.uri)
                if (hash != null) hashes[file.uri] = hash else unreadable += file.uri
                onProgress(++checked, total)
            }
        } finally {
            withContext(NonCancellable) { store(copies, stored, plan, hashes, unreadable) }
        }
        val groups = DuplicateGrouping.group(copies.map { file -> hashes[file.uri]?.let { file.copy(contentHash = it) } ?: file }) {
            plan.songKeys[it.uri]
        }
        val dismissed = dismissals.dismissed()
        return groups.filterNot { group -> dismissed.any { it.containsAll(group.copies.map(DuplicateCopy::uri)) } }
    }

    private suspend fun hashQuietly(uri: String): String? = try {
        hasher.hash(uri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Keeps what was learnt; a failure only means the next scan reads those files again. */
    private suspend fun store(
        copies: List<DuplicateCopy>,
        stored: List<DuplicateFingerprint>,
        plan: DuplicateFingerprinting.Plan,
        hashes: Map<String, String>,
        unreadable: Set<String>,
    ) {
        try {
            fingerprints.update(DuplicateFingerprinting.updates(copies, stored, plan, hashes, unreadable), plan.gone)
        } catch (_: Exception) {
            // Nothing lost but time.
        }
    }
}
