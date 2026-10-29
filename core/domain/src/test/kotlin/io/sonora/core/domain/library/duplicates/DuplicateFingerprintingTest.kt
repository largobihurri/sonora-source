package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DuplicateFingerprintingTest {

    private val a = copy("a", sizeBytes = 10)
    private val b = copy("b", sizeBytes = 10, title = "Other")
    private val c = copy("c", sizeBytes = 20)

    private fun stored(file: DuplicateCopy, hash: String? = "h-${file.uri}", key: String? = SongMatch.key(file), version: Int = SongMatch.RULES_VERSION) =
        DuplicateFingerprint(file.uri, file.sizeBytes, file.lastModifiedMs, hash, key, version)

    @Test
    fun `a first scan hashes only the files whose size another file shares`() {
        val plan = DuplicateFingerprinting.plan(listOf(a, b, c), stored = emptyList())
        assertThat(plan.toHash).containsExactly(a, b)
        assertThat(plan.hashes).isEmpty()
        assertThat(plan.songKeys).containsExactly(a.uri, SongMatch.key(a), b.uri, SongMatch.key(b), c.uri, SongMatch.key(c))
    }

    @Test
    fun `unchanged files keep their stored hash and key, changed ones are read again`() {
        val touched = b.copy(lastModifiedMs = 2_000)
        val plan = DuplicateFingerprinting.plan(listOf(a, touched, c), listOf(stored(a, key = "stored"), stored(b)))
        assertThat(plan.toHash).containsExactly(touched)
        assertThat(plan.hashes).containsExactly(a.uri, "h-${a.uri}")
        assertThat(plan.songKeys[a.uri]).isEqualTo("stored")
        assertThat(plan.songKeys[touched.uri]).isEqualTo(SongMatch.key(touched))
    }

    @Test
    fun `a file whose size changed is hashed again, and one that could not be read is tried again`() {
        val grown = a.copy(sizeBytes = 20)
        val plan = DuplicateFingerprinting.plan(listOf(grown, b, c.copy(sizeBytes = 10)), listOf(stored(a), stored(b, hash = null)))
        assertThat(plan.toHash.map { it.uri }).containsExactly(b.uri, c.uri)
        assertThat(DuplicateFingerprinting.plan(listOf(grown, c), listOf(stored(a))).toHash.map { it.uri }).containsExactly(grown.uri, c.uri)
    }

    @Test
    fun `a file that could not be read is tried again, but no longer counts as not checked until it changes`() {
        val unreadable = listOf(stored(a), stored(b, hash = DuplicateFingerprint.UNREADABLE), stored(c, hash = null))
        val plan = DuplicateFingerprinting.plan(listOf(a, b, c), unreadable)
        assertThat(plan.toHash).containsExactly(b)
        assertThat(plan.hashes).doesNotContainKey(b.uri)
        assertThat(plan.pending).isEqualTo(0)
        assertThat(plan.work).isEqualTo(1)
        assertThat(DuplicateFingerprinting.pending(listOf(a, b.copy(lastModifiedMs = 9), c), unreadable)).isEqualTo(1)
    }

    @Test
    fun `files tried and not read are stored as unreadable, a later hash replaces the mark`() {
        val plan = DuplicateFingerprinting.plan(listOf(a, b), emptyList())
        val updates = DuplicateFingerprinting.updates(listOf(a, b), emptyList(), plan, mapOf(a.uri to "h"), unreadable = setOf(b.uri))
        assertThat(updates.associate { it.uri to it.contentHash }).containsExactly(a.uri, "h", b.uri, DuplicateFingerprint.UNREADABLE)
        val again = DuplicateFingerprinting.plan(listOf(a, b), updates)
        assertThat(DuplicateFingerprinting.updates(listOf(a, b), updates, again, again.hashes + (b.uri to "h")).single().contentHash).isEqualTo("h")
    }

    @Test
    fun `keys worked out by older rules are worked out again`() {
        val plan = DuplicateFingerprinting.plan(listOf(a), listOf(stored(a, key = "old", version = SongMatch.RULES_VERSION - 1)))
        assertThat(plan.songKeys[a.uri]).isEqualTo(SongMatch.key(a))
    }

    @Test
    fun `files gone from the library are forgotten`() {
        val plan = DuplicateFingerprinting.plan(listOf(c), listOf(stored(a), stored(c)))
        assertThat(plan.gone).containsExactly(a.uri)
    }

    @Test
    fun `only new or changed fingerprints are stored, keeping hashes no longer needed`() {
        val lone = copy("lone", sizeBytes = 99)
        val stored = listOf(stored(a), stored(b), stored(lone))
        val touched = a.copy(lastModifiedMs = 5)
        val files = listOf(touched, b, c, lone)
        val plan = DuplicateFingerprinting.plan(files, stored)
        val updates = DuplicateFingerprinting.updates(files, stored, plan, plan.hashes + (touched.uri to "new"))
        assertThat(updates).containsExactly(
            DuplicateFingerprint(touched.uri, 10, 5, "new", SongMatch.key(touched), SongMatch.RULES_VERSION),
            DuplicateFingerprint(c.uri, 20, c.lastModifiedMs, null, SongMatch.key(c), SongMatch.RULES_VERSION),
        )
    }
}
