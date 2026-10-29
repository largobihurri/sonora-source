package io.sonora.core.data.library.scan

/** Bounds and spacing of tag-read retries (see [LibraryScanService]). */
internal object ReadRetry {
    /** Failed reads of a new or changed file, each at least [RETRY_SPACING_MS] apart, before it is indexed untagged. */
    const val MAX_ATTEMPTS = 3

    /** Later reads of a file indexed untagged that way, each at least [REREAD_SPACING_MS] apart. */
    const val MAX_REREADS = 3

    const val RETRY_SPACING_MS = 10 * 60 * 1000L
    const val REREAD_SPACING_MS = 24 * 60 * 60 * 1000L

    /** A stored count this high marks a file that was indexed untagged after running out of attempts. */
    fun isFallback(failures: Int): Boolean = failures >= MAX_ATTEMPTS

    /** Failed attempts on the file's current new or changed version; a fallback count belonged to an older one. */
    fun pendingAttempts(failures: Int): Int = if (isFallback(failures)) 0 else failures

    /** Whether a new or changed file that already failed [failures] times may be read again at [nowMs]. */
    fun retryDue(failures: Int, lastFailureMs: Long, nowMs: Long): Boolean =
        pendingAttempts(failures) == 0 || elapsed(lastFailureMs, nowMs, RETRY_SPACING_MS)

    /** Whether a file indexed untagged after failing (count [failures]) should be read again at [nowMs]. */
    fun rereadDue(failures: Int, lastFailureMs: Long, nowMs: Long): Boolean =
        isFallback(failures) && failures < MAX_ATTEMPTS + MAX_REREADS && elapsed(lastFailureMs, nowMs, REREAD_SPACING_MS)

    /** A clock set back before the last failure counts as elapsed, so a wrong clock cannot block reads for good. */
    private fun elapsed(sinceMs: Long, nowMs: Long, spacingMs: Long) = nowMs - sinceMs !in 0 until spacingMs
}
