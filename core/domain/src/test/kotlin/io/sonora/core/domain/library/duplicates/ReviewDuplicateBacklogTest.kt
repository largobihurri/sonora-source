package io.sonora.core.domain.library.duplicates

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.FakeLibraryMarks
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.playlist.FakePlaylists
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ReviewDuplicateBacklogTest {

    @Test
    fun `a catch-up is due from two hundred songs not checked yet`() {
        assertThat(DuplicateBacklog.needsCatchUp(pending = 199)).isFalse()
        assertThat(DuplicateBacklog.needsCatchUp(pending = 200)).isTrue()
        assertThat(DuplicateBacklog.needsCatchUp(pending = 201)).isTrue()
    }

    @Test
    fun `songs not checked yet are those without a valid fingerprint or still to hash`() {
        val a = copy("a", sizeBytes = 1)
        val b = copy("b", sizeBytes = 1)
        val c = copy("c", sizeBytes = 2)
        val checked = { file: DuplicateCopy, hash: String? ->
            DuplicateFingerprint(file.uri, file.sizeBytes, file.lastModifiedMs, hash, SongMatch.key(file), SongMatch.RULES_VERSION)
        }
        assertThat(DuplicateFingerprinting.pending(listOf(a, b, c), emptyList())).isEqualTo(3)
        assertThat(DuplicateFingerprinting.pending(listOf(a, b, c), listOf(checked(a, "h"), checked(b, null), checked(c, null)))).isEqualTo(1)
        assertThat(DuplicateFingerprinting.pending(listOf(a, b, c), listOf(checked(a, "h"), checked(b, "h"), checked(c, null)))).isEqualTo(0)
    }

    private val library = FakeDuplicateLibrary(emptyList())
    private val fingerprints = FakeFingerprints()
    private val firstRun = FakeFirstRun()
    private val work = FakeWork()
    private val dismissals = FakeDismissals()
    private val scope = CoroutineScope(Job())
    private val scan = DuplicateScan(
        FindDuplicates(library, FakeHasher(), fingerprints, dismissals),
        library,
        dismissals,
        DeleteDuplicateCopies(DeleteFromPhone(NoFiles, FakePlaylists(), FakeLibraryMarks())),
        scope,
    )
    private val review = ReviewDuplicateBacklog(library, fingerprints, firstRun, work, scan)

    private fun songs(count: Int, prefix: String = "s") = (1..count).map { copy("$prefix$it", title = "Song $prefix$it") }

    @Test
    fun `nothing happens while the library is empty`() = runBlocking<Unit> {
        assertThat(review()).isEqualTo(BacklogAction.NONE)
        assertThat(firstRun.done).isFalse()
        assertThat(scan.state.value).isEqualTo(DuplicateScanState.NotScanned)
    }

    @Test
    fun `the first library with songs starts an announced analysis at once, only once`() = runBlocking<Unit> {
        library.files = songs(500)
        assertThat(review()).isEqualTo(BacklogAction.FIRST_ANALYSIS)
        assertThat(firstRun.done).isTrue()
        assertThat(scan.announced.value).isTrue()
        withTimeout(5_000) { scan.state.first { it is DuplicateScanState.Found } }
        assertThat(work.catchUps).isEqualTo(0)
        assertThat(review()).isEqualTo(BacklogAction.NONE)
    }

    @Test
    fun `later, two hundred new songs or more ask for a catch-up, fewer wait for the periodic scan`() = runBlocking<Unit> {
        firstRun.done = true
        library.files = songs(199)
        assertThat(review()).isEqualTo(BacklogAction.NONE)
        library.files = songs(200)
        assertThat(review()).isEqualTo(BacklogAction.CATCH_UP)
        assertThat(work.catchUps).isEqualTo(1)
        assertThat(scan.announced.value).isFalse()
    }

    private object NoFiles : LibraryFileDeleter {
        override suspend fun writeAccessFor(uri: String) = null

        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) = WriteGrant.GRANTED

        override suspend fun delete(uri: String) = false
    }
}
