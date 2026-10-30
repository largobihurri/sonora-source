package io.sonora.core.domain.library.duplicates

/** What [ReviewDuplicateBacklog] started. */
enum class BacklogAction { NONE, FIRST_ANALYSIS, CATCH_UP }

/** When songs not checked for duplicates yet are too many to wait for the daily low-cost scan. */
object DuplicateBacklog {
    const val CATCH_UP_THRESHOLD = 200

    fun needsCatchUp(pending: Int): Boolean = pending >= CATCH_UP_THRESHOLD
}

/**
 * Run after each library scan. The first time the library has songs, the analysis starts at once in the app, announced
 * (it may use more resources for those first minutes), and never again that way. Later, [DuplicateBacklog] songs or
 * more not checked yet ask for a catch-up scan that does not wait for the phone to be idle and charging; fewer are left
 * to the daily scan, and make the last result stale, so the Duplicates page analyses again when it opens.
 */
class ReviewDuplicateBacklog(
    private val library: DuplicateLibrary,
    private val fingerprints: DuplicateFingerprints,
    private val firstRun: DuplicateFirstRun,
    private val work: DuplicateWork,
    private val scan: DuplicateScan,
) {
    suspend operator fun invoke(): BacklogAction {
        val copies = library.copies()
        if (copies.isEmpty()) return BacklogAction.NONE
        if (!firstRun.done()) {
            firstRun.markDone()
            scan.scan(announce = true)
            return BacklogAction.FIRST_ANALYSIS
        }
        val pending = DuplicateFingerprinting.pending(copies, fingerprints.all())
        if (!DuplicateBacklog.needsCatchUp(pending)) {
            if (pending > 0) scan.markStale()
            return BacklogAction.NONE
        }
        work.catchUp()
        return BacklogAction.CATCH_UP
    }
}
