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
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CheckDuplicatesTest {

    @Test
    fun `the notification is due from twenty groups, and again only once more have piled up`() {
        assertThat(DuplicateAlertPolicy.shouldNotify(groups = 19, lastNotifiedCount = 0, enabled = true)).isFalse()
        assertThat(DuplicateAlertPolicy.shouldNotify(groups = 20, lastNotifiedCount = 0, enabled = true)).isTrue()
        assertThat(DuplicateAlertPolicy.shouldNotify(groups = 20, lastNotifiedCount = 20, enabled = true)).isFalse()
        assertThat(DuplicateAlertPolicy.shouldNotify(groups = 21, lastNotifiedCount = 20, enabled = true)).isTrue()
        assertThat(DuplicateAlertPolicy.shouldNotify(groups = 40, lastNotifiedCount = 0, enabled = false)).isFalse()
    }

    @Test
    fun `the count of the last notification follows the groups down, and up only when posted`() {
        assertThat(DuplicateAlertPolicy.lastNotifiedAfter(groups = 25, lastNotifiedCount = 20, posted = true)).isEqualTo(25)
        assertThat(DuplicateAlertPolicy.lastNotifiedAfter(groups = 5, lastNotifiedCount = 25, posted = false)).isEqualTo(5)
        assertThat(DuplicateAlertPolicy.lastNotifiedAfter(groups = 30, lastNotifiedCount = 5, posted = false)).isEqualTo(5)
    }

    /** A library of [count] duplicate groups, two copies each. */
    private fun library(count: Int) = FakeDuplicateLibrary(
        (1..count).flatMap { listOf(copy("s$it", title = "Song $it"), mp3("m$it", title = "Song $it")) },
    )

    private val alerts = FakeAlerts()
    private val notifier = FakeNotifier()

    private fun check(library: FakeDuplicateLibrary): Pair<CheckDuplicates, DuplicateScan> {
        val dismissals = FakeDismissals()
        val scan = DuplicateScan(
            FindDuplicates(library, FakeHasher(), FakeFingerprints(), dismissals),
            library,
            dismissals,
            DeleteDuplicateCopies(DeleteFromPhone(NoFiles, FakePlaylists(), FakeLibraryMarks())),
            CoroutineScope(Job()),
        )
        return CheckDuplicates(scan, alerts, notifier) to scan
    }

    @Test
    fun `a periodic check posts once twenty groups piled up, and not again for the same count`() = runBlocking<Unit> {
        val library = library(19)
        val (check, _) = check(library)
        assertThat(check()).isTrue()
        assertThat(notifier.posted).isEmpty()
        library.files = library(20).files
        check()
        check()
        assertThat(notifier.posted).containsExactly(20)
        library.files = library(22).files
        check()
        assertThat(notifier.posted).containsExactly(20, 22).inOrder()
    }

    @Test
    fun `with the switch off the check still scans but posts nothing`() = runBlocking<Unit> {
        alerts.enabled.value = false
        val (check, scan) = check(library(25))
        check()
        assertThat(notifier.posted).isEmpty()
        assertThat((scan.state.value as DuplicateScanState.Found).groups).hasSize(25)
        alerts.enabled.value = true
        check()
        assertThat(notifier.posted).containsExactly(25)
    }

    @Test
    fun `a notification that could not be posted is tried again next time`() = runBlocking<Unit> {
        notifier.allowed = false
        val (check, _) = check(library(20))
        check()
        notifier.allowed = true
        check()
        assertThat(notifier.posted).containsExactly(20)
    }

    @Test
    fun `a failed scan posts nothing and asks to be retried`() = runBlocking<Unit> {
        val library = library(20).apply { fails = true }
        val (check, _) = check(library)
        assertThat(check()).isFalse()
        assertThat(notifier.posted).isEmpty()
    }

    private object NoFiles : LibraryFileDeleter {
        override suspend fun writeAccessFor(uri: String) = null

        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) =
            WriteGrant.GRANTED

        override suspend fun delete(uri: String) = false
    }
}
