package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class FindDuplicatesTest {

    private val flac = copy("flac", sizeBytes = 30)
    private val mp3 = mp3("mp3").copy(sizeBytes = 40)
    private val one = copy("one", title = "Other", sizeBytes = 50)
    private val two = copy("two", title = null, artist = null, sizeBytes = 50)
    private val lone = copy("lone", title = "Lone", sizeBytes = 60)

    private val library = FakeDuplicateLibrary(listOf(flac, mp3, one, two, lone))
    private val hasher = FakeHasher(mutableMapOf(one.uri to "same", two.uri to "same"))
    private val fingerprints = FakeFingerprints()
    private val dismissals = FakeDismissals()
    private val find = FindDuplicates(library, hasher, fingerprints, dismissals)

    private fun names(groups: List<DuplicateGroup>) = groups.map { group -> group.copies.map { it.uri.substringAfterLast('/') }.toSet() }

    @Test
    fun `finds identical files and the same song, hashing only files of equal size, with progress over new files`() = runBlocking<Unit> {
        val progress = mutableListOf<Pair<Int, Int>>()
        val groups = find { hashed, toHash -> progress += hashed to toHash }
        assertThat(names(groups)).containsExactly(setOf("one", "two"), setOf("flac", "mp3"))
        assertThat(groups.first { it.copies.size == 2 && it.kind == DuplicateKind.IDENTICAL }.copies.map { it.contentHash }).containsExactly("same", "same")
        assertThat(hasher.hashed).containsExactly(one.uri, two.uri)
        // Five new files: three need no reading (their keys are worked out at once), two are hashed.
        assertThat(progress).containsExactly(3 to 5, 4 to 5, 5 to 5).inOrder()
    }

    @Test
    fun `a second scan reads only new or changed files`() = runBlocking<Unit> {
        find()
        hasher.hashed.clear()
        val three = copy("three", title = null, artist = null, sizeBytes = 50)
        hasher.contents[three.uri] = "same"
        library.files = library.files + three
        val groups = find()
        assertThat(hasher.hashed).containsExactly(three.uri)
        assertThat(names(groups)).contains(setOf("one", "two", "three"))
        hasher.hashed.clear()
        find()
        assertThat(hasher.hashed).isEmpty()
    }

    @Test
    fun `a dismissed group stays hidden as its copies go, and comes back when a new copy joins`() = runBlocking<Unit> {
        dismissals.dismiss(setOf(flac.uri, mp3.uri))
        assertThat(names(find())).containsExactly(setOf("one", "two"))
        val aac = mp3("aac", kbps = 256).copy(format = "AAC", sizeBytes = 70)
        library.files = library.files + aac
        assertThat(names(find())).contains(setOf("flac", "mp3", "aac"))
    }

    @Test
    fun `files that cannot be read are not linked by their content, the scan goes on`() = runBlocking<Unit> {
        val broken = copy("throws", title = null, artist = null, sizeBytes = 50)
        library.files = library.files + broken
        hasher.contents.remove(two.uri)
        val groups = find()
        assertThat(names(groups)).containsExactly(setOf("flac", "mp3"))
        assertThat(hasher.hashed).containsExactly(one.uri, two.uri, broken.uri)
    }

    @Test
    fun `a file that cannot be read is marked so it leaves the backlog, and is tried again next scan`() = runBlocking<Unit> {
        hasher.contents.remove(two.uri)
        find()
        assertThat(fingerprints.stored[two.uri]?.contentHash).isEqualTo(DuplicateFingerprint.UNREADABLE)
        assertThat(DuplicateFingerprinting.pending(library.files, fingerprints.all())).isEqualTo(0)
        hasher.hashed.clear()
        hasher.contents[two.uri] = "same"
        val progress = mutableListOf<Pair<Int, Int>>()
        val groups = find { checked, total -> progress += checked to total }
        assertThat(hasher.hashed).containsExactly(two.uri)
        assertThat(progress).containsExactly(0 to 1, 1 to 1).inOrder()
        assertThat(names(groups)).contains(setOf("one", "two"))
        assertThat(fingerprints.stored[two.uri]?.contentHash).isEqualTo("same")
    }

    @Test
    fun `stopping halfway keeps the hashes already worked out`() = runBlocking<Unit> {
        hasher.gated = two.uri
        val job = launch { find() }
        yield()
        job.cancel()
        job.join()
        assertThat(fingerprints.stored[one.uri]?.contentHash).isEqualTo("same")
        assertThat(fingerprints.stored[two.uri]?.contentHash).isNull()
        hasher.hashed.clear()
        hasher.gated = null
        find()
        assertThat(hasher.hashed).containsExactly(two.uri)
    }
}
