package io.sonora.core.domain.library.duplicates

import kotlinx.coroutines.flow.Flow

/** The library's files as the duplicate finder compares them, hidden songs included. Main-safe. */
interface DuplicateLibrary {
    suspend fun copies(): List<DuplicateCopy>
}

/** Reads a file's bytes for [DuplicateCopy.contentHash]. Main-safe; cancellable between chunks. */
interface ContentHasher {
    /** SHA-256 of the file at [uri], hex; `null` when it cannot be read. */
    suspend fun hash(uri: String): String?
}

/** Groups the user marked "Not a duplicate", each as the set of its files' URIs. */
interface DuplicateDismissals {
    suspend fun dismissed(): List<Set<String>>

    suspend fun dismiss(uris: Set<String>)
}

/**
 * What the last scans learnt about each file: its content hash and song key, valid while its size and modification
 * time stay the same, so a scan only reads files that are new or changed.
 */
data class DuplicateFingerprint(
    val uri: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val contentHash: String?,
    val songKey: String?,
    /** The [SongMatch.RULES_VERSION] [songKey] was worked out with. */
    val rulesVersion: Int,
) {
    companion object {
        /**
         * The [contentHash] of a file a scan tried and could not read (never a hex hash): it is tried again on every
         * scan, but no longer counts as not checked until it changes, so it never keeps a backlog alive.
         */
        const val UNREADABLE = "unreadable"
    }
}

/** The stored [DuplicateFingerprint]s. Main-safe. */
interface DuplicateFingerprints {
    suspend fun all(): List<DuplicateFingerprint>

    /** Stores [fingerprints] and drops those of the files in [gone], at once. */
    suspend fun update(fingerprints: List<DuplicateFingerprint>, gone: Collection<String>)
}

/** The duplicates notification's switch and when it was last posted. */
interface DuplicateAlerts {
    /** Whether the user wants the notification (on until turned off). */
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)

    /** The number of groups the last notification told of, lowered as groups go; 0 at first. */
    suspend fun lastNotifiedCount(): Int

    suspend fun setLastNotifiedCount(count: Int)
}

/** Posts the "duplicates piled up" notification. */
interface DuplicateNotifier {
    /** Tells of [groups] duplicate groups; `false` when it could not be posted (notifications not allowed). */
    fun notify(groups: Int): Boolean
}

/** Whether the first analysis of a new library ran (it runs once, at once, announced in the app). */
interface DuplicateFirstRun {
    suspend fun done(): Boolean

    suspend fun markDone()
}

/** The background duplicate scans. */
interface DuplicateWork {
    /** The low-cost daily scan (idle, charging, battery and storage not low); kept when already scheduled. */
    fun schedulePeriodic()

    /** One scan as soon as the battery is not low, to work off a backlog of songs not checked yet. */
    fun catchUp()
}
