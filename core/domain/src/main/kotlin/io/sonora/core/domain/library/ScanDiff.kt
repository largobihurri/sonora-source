package io.sonora.core.domain.library

/** What identifies one file's content between scans: a changed size or modification time means re-read it. */
data class FileIdentity(
    val documentUri: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/** Incremental scan plan: only [added] and [changed] files need their tags read; [removed] leave the index. */
data class ScanDiff(
    val added: List<FileIdentity>,
    val changed: List<FileIdentity>,
    val unchanged: List<FileIdentity>,
    val removed: List<FileIdentity>,
) {
    companion object {
        /**
         * Compares the [indexed] entries with the [current] listing by document URI. Listing order is kept;
         * a URI listed twice counts once (last listing wins).
         */
        fun compute(indexed: Collection<FileIdentity>, current: Collection<FileIdentity>): ScanDiff {
            val known = indexed.associateBy { it.documentUri }
            val listed = current.associateBy { it.documentUri }.values
            val added = mutableListOf<FileIdentity>()
            val changed = mutableListOf<FileIdentity>()
            val unchanged = mutableListOf<FileIdentity>()
            for (file in listed) {
                when (known[file.documentUri]) {
                    null -> added += file
                    file -> unchanged += file
                    else -> changed += file
                }
            }
            val listedUris = listed.mapTo(HashSet()) { it.documentUri }
            val removed = indexed.filter { it.documentUri !in listedUris }
            return ScanDiff(added, changed, unchanged, removed)
        }
    }
}
