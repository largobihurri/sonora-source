package io.sonora.core.domain.library.duplicates

/**
 * What a scan has to read, given what earlier scans stored ([DuplicateFingerprint]): a stored fingerprint holds while
 * the file keeps its size and modification time. Only files whose size another file shares need a content hash (two
 * files of different sizes cannot be identical); one already worked out is reused, so a scan hashes only new or
 * changed files. Song keys are cheap, but are kept the same way, and worked out again under newer [SongMatch] rules.
 */
object DuplicateFingerprinting {

    /**
     * [toHash]: files to read, in library order. [hashes] and [songKeys]: what is known without reading (every file has
     * a song key entry, `null` when it has none). [gone]: stored files no longer in the library. [pending]: files not
     * checked yet (no valid fingerprint under the current rules, or a hash still to work out); files already found
     * [DuplicateFingerprint.UNREADABLE] are read again but not counted. [work]: the files this scan checks, those too.
     */
    data class Plan(
        val toHash: List<DuplicateCopy>,
        val hashes: Map<String, String>,
        val songKeys: Map<String, String?>,
        val gone: Set<String>,
        val pending: Int = toHash.size,
        val work: Int = pending,
    )

    fun plan(copies: List<DuplicateCopy>, stored: List<DuplicateFingerprint>): Plan {
        val valid = validFingerprints(copies, stored)
        val songKeys = copies.associate { file ->
            val known = valid[file.uri]?.takeIf { it.rulesVersion == SongMatch.RULES_VERSION }
            file.uri to if (known != null) known.songKey else SongMatch.key(file)
        }
        val hashes = HashMap<String, String>()
        val toHash = ArrayList<DuplicateCopy>()
        for (file in sizeColliding(copies)) {
            val hash = valid[file.uri]?.contentHash
            if (hash != null && hash != DuplicateFingerprint.UNREADABLE) hashes[file.uri] = hash else toHash += file
        }
        val present = copies.mapTo(HashSet()) { it.uri }
        val unchecked = copies.filter { valid[it.uri]?.rulesVersion != SongMatch.RULES_VERSION }.mapTo(HashSet()) { it.uri }
        toHash.mapTo(unchecked) { it.uri }
        val retried = toHash.count { valid[it.uri]?.let(::knownUnreadable) == true }
        val gone = stored.mapNotNullTo(LinkedHashSet()) { it.uri.takeIf { uri -> uri !in present } }
        return Plan(toHash, hashes, songKeys, gone, pending = unchecked.size - retried, work = unchecked.size)
    }

    /** Found unreadable by a scan under the current rules: only a change to the file makes it count as not checked. */
    private fun knownUnreadable(fingerprint: DuplicateFingerprint) =
        fingerprint.contentHash == DuplicateFingerprint.UNREADABLE && fingerprint.rulesVersion == SongMatch.RULES_VERSION

    /**
     * How many files are not checked yet: no valid fingerprint under the current rules, or a hash still to work out
     * (a file found unreadable does not count until it changes).
     */
    fun pending(copies: List<DuplicateCopy>, stored: List<DuplicateFingerprint>): Int = plan(copies, stored).pending

    /**
     * The fingerprints to store after a scan that worked out [hashes] and tried the [unreadable] files in vain: those new
     * or different from [stored]. An unreadable file is stored as [DuplicateFingerprint.UNREADABLE].
     */
    fun updates(
        copies: List<DuplicateCopy>,
        stored: List<DuplicateFingerprint>,
        plan: Plan,
        hashes: Map<String, String>,
        unreadable: Set<String> = emptySet(),
    ): List<DuplicateFingerprint> {
        val valid = validFingerprints(copies, stored)
        val before = stored.associateBy { it.uri }
        return copies.mapNotNull { file ->
            val fingerprint = DuplicateFingerprint(
                uri = file.uri,
                sizeBytes = file.sizeBytes,
                lastModifiedMs = file.lastModifiedMs,
                contentHash = hashes[file.uri] ?: DuplicateFingerprint.UNREADABLE.takeIf { file.uri in unreadable } ?: valid[file.uri]?.contentHash,
                songKey = plan.songKeys[file.uri],
                rulesVersion = SongMatch.RULES_VERSION,
            )
            fingerprint.takeIf { it != before[file.uri] }
        }
    }

    /** Files of a size another file shares. */
    fun sizeColliding(copies: List<DuplicateCopy>): List<DuplicateCopy> {
        val counts = copies.filter { it.sizeBytes > 0 }.groupingBy { it.sizeBytes }.eachCount()
        return copies.filter { (counts[it.sizeBytes] ?: 0) > 1 }
    }

    /** Stored fingerprints still describing the file: same size and modification time. */
    private fun validFingerprints(copies: List<DuplicateCopy>, stored: List<DuplicateFingerprint>): Map<String, DuplicateFingerprint> {
        val byUri = stored.associateBy { it.uri }
        return copies.mapNotNull { file ->
            byUri[file.uri]?.takeIf { it.sizeBytes == file.sizeBytes && it.lastModifiedMs == file.lastModifiedMs }
        }.associateBy { it.uri }
    }
}
