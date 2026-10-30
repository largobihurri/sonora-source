package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.FakeLibraryMarks
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.playlist.FakePlaylists
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class DuplicateScanTest {

    private val a = copy("a")
    private val b = mp3("b")
    private val c = mp3("c", kbps = 128)
    private val other1 = copy("o1", title = "Other")
    private val other2 = mp3("o2", title = "Other")
    private val library = FakeDuplicateLibrary(listOf(a, b, c, other1, other2))
    private val hasher = FakeHasher()
    private val dismissals = FakeDismissals()

    /** Deletes by dropping the file from [library]. */
    private val files = object : LibraryFileDeleter {
        override suspend fun writeAccessFor(uri: String): WriteAccessRequest? = null

        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) = WriteGrant.GRANTED

        override suspend fun delete(uri: String): Boolean {
            library.files = library.files.filterNot { it.uri == uri }
            return true
        }
    }

    private fun CoroutineScope.scanner() = DuplicateScan(
        FindDuplicates(library, hasher, FakeFingerprints(), dismissals),
        library,
        dismissals,
        DeleteDuplicateCopies(DeleteFromPhone(files, FakePlaylists(), FakeLibraryMarks())),
        this,
    )

    /** The groups once found; a scan that never finds fails the test instead of hanging. */
    private suspend fun DuplicateScan.found() =
        (withTimeout(5_000) { state.first { it is DuplicateScanState.Found } } as DuplicateScanState.Found).groups

    @Test
    fun `a scan runs in the background and shows its groups`() = runBlocking<Unit> {
        val scan = scanner()
        assertThat(scan.state.value).isEqualTo(DuplicateScanState.NotScanned)
        scan.scanIfNeeded()
        assertThat(scan.found().map { it.copies.size }).containsExactly(2, 3)
    }

    @Test
    fun `a scan already found or running is not started again unless asked`() = runBlocking<Unit> {
        val scan = scanner()
        scan.scan()
        scan.scan()
        scan.found()
        library.files = listOf(a, b)
        scan.scanIfNeeded()
        yield()
        assertThat(scan.found()).hasSize(2)
        scan.scan()
        yield()
        assertThat(scan.found()).hasSize(1)
    }

    @Test
    fun `a result marked stale is scanned again when needed, once`() = runBlocking<Unit> {
        val scan = scanner()
        scan.scan()
        assertThat(scan.found()).hasSize(2)
        library.files = listOf(a, b)
        scan.markStale()
        scan.scanIfNeeded()
        yield()
        assertThat(scan.found()).hasSize(1)
        library.files = listOf(other1, other2)
        scan.scanIfNeeded()
        yield()
        assertThat(scan.found().single().copies).containsExactly(a, b)
    }

    @Test
    fun `a failed scan says so and can be retried`() = runBlocking<Unit> {
        val scan = scanner()
        library.fails = true
        assertThat(scan.scanNow()).isNull()
        assertThat(scan.state.value).isEqualTo(DuplicateScanState.Failed)
        library.fails = false
        scan.scanIfNeeded()
        assertThat(scan.found()).hasSize(2)
    }

    @Test
    fun `a failed first analysis ends its announcement, so a later scan is not announced`() = runBlocking<Unit> {
        val scan = scanner()
        library.fails = true
        scan.scan(announce = true)
        assertThat(scan.announced.value).isTrue()
        withTimeout(5_000) { scan.state.first { it == DuplicateScanState.Failed } }
        assertThat(scan.announced.value).isFalse()
        library.fails = false
        scan.scan()
        scan.found()
        assertThat(scan.announced.value).isFalse()
    }

    @Test
    fun `a stopped scan leaves what was found before`() = runBlocking<Unit> {
        val scan = scanner()
        scan.scanNow()
        val before = scan.state.value
        hasher.gated = a.uri
        library.files = library.files + copy("a2", sizeBytes = a.sizeBytes)
        val job = launch { scan.scanNow() }
        yield()
        assertThat(scan.state.value).isInstanceOf(DuplicateScanState.Scanning::class.java)
        job.cancel()
        job.join()
        assertThat(scan.state.value).isEqualTo(before)
    }

    @Test
    fun `a dismissed group leaves the list for good`() = runBlocking<Unit> {
        val scan = scanner()
        val group = scan.scanNow()!!.first { it.copies.size == 2 }
        scan.dismiss(group)
        assertThat(scan.found().map { it.key }).doesNotContain(group.key)
        assertThat(dismissals.sets).containsExactly(group.copies.map { it.uri }.toSet())
        assertThat(scan.scanNow()!!.map { it.key }).doesNotContain(group.key)
    }

    @Test
    fun `deleting copies updates their group, and a group down to one copy leaves the list`() = runBlocking<Unit> {
        val scan = scanner()
        val groups = scan.scanNow()!!
        val song = groups.first { it.copies.size == 3 }
        assertThat(scan.delete(song, setOf(c.uri))).isEqualTo(DuplicateDeletion.Deleted(1, 0))
        assertThat(scan.found().first { it.suggested.uri == a.uri }.copies.map { it.uri }).containsExactly(a.uri, b.uri).inOrder()
        val other = groups.first { it.copies.size == 2 }
        scan.delete(other, setOf(other2.uri))
        assertThat(scan.found().map { it.suggested.uri }).containsExactly(a.uri)
    }
}
