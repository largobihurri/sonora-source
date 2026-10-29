package io.sonora.core.domain.model

/** Where the library scanner is. */
sealed interface ScanProgress {
    data object Idle : ScanProgress

    /** Walking or reading [folderName]; [scanned] of [total] audio files in that folder are done. */
    data class Scanning(val folderName: String, val scanned: Int, val total: Int) : ScanProgress

    data class Finished(val summary: ScanSummary) : ScanProgress
}

/**
 * Outcome of one scan. [failedFolders] could not be listed (access revoked, volume missing) and kept their tracks;
 * [skipped] files could not be opened or read right now and are retried by the next scan.
 */
data class ScanSummary(
    val added: Int = 0,
    val changed: Int = 0,
    val unchanged: Int = 0,
    val removed: Int = 0,
    val failedFolders: Int = 0,
    val skipped: Int = 0,
) {
    operator fun plus(other: ScanSummary) = ScanSummary(
        added = added + other.added,
        changed = changed + other.changed,
        unchanged = unchanged + other.unchanged,
        removed = removed + other.removed,
        failedFolders = failedFolders + other.failedFolders,
        skipped = skipped + other.skipped,
    )
}
