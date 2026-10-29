package io.sonora.core.data.library.scan

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDao
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.data.library.db.LibraryFolderEntity
import io.sonora.core.data.library.db.TrackEntity
import io.sonora.core.data.library.saf.ListedAudioFile
import io.sonora.core.data.library.tags.ArtworkCache
import io.sonora.core.data.library.tags.TagReadResult
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ScanSummary
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryScanServiceTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var service: LibraryScanService

    private val folder = LibraryFolderEntity("content://tree/music", "Music", addedAtMs = 1)

    /** Listing per tree URI; a missing entry fails like a revoked grant. */
    private val listings = mutableMapOf<String, List<ListedAudioFile>>()

    /** File names whose next read fails transiently (consumed by the read). */
    private val unreadable = mutableSetOf<String>()

    /** File names whose every read fails transiently. */
    private val neverReadable = mutableSetOf<String>()
    private val reads = mutableListOf<String>()
    private var onRead: (ListedAudioFile) -> Unit = {}

    /** The scanner's wall clock. */
    private var now = 1_000_000L

    /** Moves the clock past the spacing between two reads of a file that could not be read. */
    private fun afterRetrySpacing() {
        now += ReadRetry.RETRY_SPACING_MS
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = db.libraryDao()
        service = newService()
    }

    /** A fresh service over the same index, like the next app start. */
    private fun newService() = LibraryScanService(
        dao = dao,
        walker = { treeUri, _ -> listings[treeUri] ?: throw IOException("Cannot list $treeUri") },
        tagReader = { file ->
            reads += file.fileName
            onRead(file)
            if (unreadable.remove(file.fileName) || file.fileName in neverReadable) {
                null
            } else {
                TagReadResult(TrackTags(title = file.fileName, artist = "Band", album = "Record"), null)
            }
        },
        artworkCache = ArtworkCache(RuntimeEnvironment.getApplication()),
        clock = { now },
    )

    @After
    fun tearDown() = db.close()

    private fun file(name: String, size: Long = 100, folderPath: String = "Music/Record") = ListedAudioFile(
        documentUri = "${folder.treeUri}/document/$name",
        fileName = name,
        mimeType = "audio/flac",
        sizeBytes = size,
        lastModifiedMs = 1_000,
        folderPath = folderPath,
        folderImageUri = null,
    )

    private suspend fun indexedNames() = dao.allTracks().map { it.fileName }.sorted()

    private suspend fun addFolder(vararg files: ListedAudioFile) {
        dao.insertFolder(folder)
        listings[folder.treeUri] = files.toList()
    }

    @Test
    fun `first scan indexes every listed file and reports it`() = runTest {
        addFolder(file("a.flac"), file("b.flac"), file("c.flac"))

        val summary = service.scan()

        assertThat(summary).isEqualTo(ScanSummary(added = 3))
        assertThat(service.progress.value).isEqualTo(ScanProgress.Finished(summary))
        assertThat(indexedNames()).containsExactly("a.flac", "b.flac", "c.flac").inOrder()
        assertThat(dao.allTracks().map { it.albumId }.toSet()).hasSize(1)
    }

    @Test
    fun `rescan reads only new and changed files and deletes removed ones`() = runTest {
        addFolder(file("a.flac"), file("b.flac"), file("c.flac"))
        service.scan()
        reads.clear()
        listings[folder.treeUri] = listOf(file("a.flac"), file("b.flac", size = 200), file("d.flac"))

        val summary = service.scan()

        assertThat(reads).containsExactly("b.flac", "d.flac")
        assertThat(summary).isEqualTo(ScanSummary(added = 1, changed = 1, unchanged = 1, removed = 1))
        assertThat(indexedNames()).containsExactly("a.flac", "b.flac", "d.flac").inOrder()
        assertThat(dao.allTracks().single { it.fileName == "b.flac" }.sizeBytes).isEqualTo(200)
    }

    @Test
    fun `a folder whose listing fails keeps its indexed tracks`() = runTest {
        addFolder(file("a.flac"), file("b.flac"))
        service.scan()
        listings.remove(folder.treeUri)

        val summary = service.scan()

        assertThat(summary).isEqualTo(ScanSummary(failedFolders = 1))
        assertThat(indexedNames()).containsExactly("a.flac", "b.flac").inOrder()
    }

    @Test
    fun `a file that cannot be read right now is skipped and retried by the next scan`() = runTest {
        addFolder(file("a.flac"), file("b.flac"))
        unreadable += "b.flac"

        val first = service.scan()

        assertThat(first).isEqualTo(ScanSummary(added = 1, skipped = 1))
        assertThat(indexedNames()).containsExactly("a.flac")
        reads.clear()
        afterRetrySpacing()

        val second = service.scan()

        assertThat(reads).containsExactly("b.flac")
        assertThat(second).isEqualTo(ScanSummary(added = 1, unchanged = 1))
        assertThat(indexedNames()).containsExactly("a.flac", "b.flac").inOrder()
    }

    @Test
    fun `a file that fails on three spaced scans in a row is indexed untagged`() = runTest {
        addFolder(file("a.flac"), file("b.flac"))
        neverReadable += "b.flac"

        assertThat(service.scan()).isEqualTo(ScanSummary(added = 1, skipped = 1))
        afterRetrySpacing()
        assertThat(newService().scan()).isEqualTo(ScanSummary(unchanged = 1, skipped = 1)) // count survives restarts
        afterRetrySpacing()
        assertThat(newService().scan()).isEqualTo(ScanSummary(added = 1, unchanged = 1))

        val fallback = dao.allTracks().single { it.fileName == "b.flac" }
        assertThat(fallback.tagTitle).isNull()
        assertThat(fallback.title).isNotEmpty()
        reads.clear()
        assertThat(service.scan()).isEqualTo(ScanSummary(unchanged = 2))
        assertThat(reads).isEmpty()
    }

    @Test
    fun `a successful read resets the failure count`() = runTest {
        addFolder(file("a.flac"))
        unreadable += "a.flac"
        service.scan()
        unreadable += "a.flac"
        afterRetrySpacing()
        service.scan()
        afterRetrySpacing()
        assertThat(service.scan()).isEqualTo(ScanSummary(added = 1))

        listings[folder.treeUri] = listOf(file("a.flac", size = 200))
        repeat(2) {
            unreadable += "a.flac"
            afterRetrySpacing()
            assertThat(service.scan()).isEqualTo(ScanSummary(skipped = 1))
        }

        assertThat(dao.allTracks().single().tagTitle).isEqualTo("a.flac")
        assertThat(dao.allTracks().single().sizeBytes).isEqualTo(100)
    }

    @Test
    fun `a file that leaves the folder forgets its failures`() = runTest {
        addFolder(file("a.flac"))
        neverReadable += "a.flac"
        repeat(2) {
            service.scan()
            afterRetrySpacing()
        }
        listings[folder.treeUri] = emptyList()
        service.scan()
        listings[folder.treeUri] = listOf(file("a.flac"))

        assertThat(service.scan()).isEqualTo(ScanSummary(skipped = 1))
        assertThat(dao.allTracks()).isEmpty()
    }

    @Test
    fun `a changed file that cannot be read keeps its previous entry until it is read`() = runTest {
        addFolder(file("a.flac"))
        service.scan()
        listings[folder.treeUri] = listOf(file("a.flac", size = 200))
        unreadable += "a.flac"

        assertThat(service.scan()).isEqualTo(ScanSummary(skipped = 1))
        assertThat(dao.allTracks().single().sizeBytes).isEqualTo(100)

        reads.clear()
        afterRetrySpacing()
        assertThat(service.scan()).isEqualTo(ScanSummary(changed = 1))
        assertThat(reads).containsExactly("a.flac")
        assertThat(dao.allTracks().single().sizeBytes).isEqualTo(200)
    }

    @Test
    fun `cancelling a scan keeps whole batches grouped and the next scan resumes`() = runTest {
        val files = (1..30).map { file("%02d.flac".format(it)) }
        addFolder(*files.toTypedArray())
        lateinit var scan: Job
        onRead = { if (reads.size == 27) scan.cancel() }

        scan = launch { service.scan() }
        scan.join()

        val indexed = dao.allTracks()
        assertThat(indexed).hasSize(25)
        assertThat(indexed.map { it.albumId }.toSet()).hasSize(1)
        assertThat(indexed.none { it.title.isEmpty() }).isTrue()
        assertThat(service.progress.value).isNotInstanceOf(ScanProgress.Scanning::class.java)

        reads.clear()
        onRead = {}
        assertThat(service.scan()).isEqualTo(ScanSummary(added = 5, unchanged = 25))
        assertThat(reads).hasSize(5)
    }

    @Test
    fun `progress ends in a terminal state and the scan failure surfaces when regrouping fails too`() = runTest {
        addFolder(file("a.flac"))
        onRead = {
            db.close() // regrouping in the scan's cleanup now fails as well
            throw IllegalStateException("reader exploded")
        }

        val error = assertThrows(IllegalStateException::class.java) { runBlocking { service.scan() } }

        assertThat(error).hasMessageThat().isEqualTo("reader exploded")
        assertThat(service.progress.value).isEqualTo(ScanProgress.Idle)
    }

    @Test
    fun `scanning without folders does nothing and stays idle`() = runTest {
        assertThat(service.scan()).isEqualTo(ScanSummary())
        assertThat(service.progress.value).isEqualTo(ScanProgress.Idle)
        assertThat(reads).isEmpty()
    }

    @Test
    fun `a folder removed during its scan stops being read and leaves no tracks`() = runTest {
        addFolder(*(1..60).map { file("%02d.flac".format(it)) }.toTypedArray())
        onRead = { if (reads.size == 3) runBlocking { dao.removeFolder(folder.treeUri) } }

        service.scan()

        assertThat(reads).hasSize(25)
        assertThat(dao.allTracks()).isEmpty()
    }

    /** Indexes [name] untagged after [ReadRetry.MAX_ATTEMPTS] spaced failed reads. */
    private suspend fun giveUpOn(name: String) {
        neverReadable += name
        repeat(ReadRetry.MAX_ATTEMPTS) {
            service.scan()
            afterRetrySpacing()
        }
        assertThat(dao.allTracks().single { it.fileName == name }.tagTitle).isNull()
    }

    @Test
    fun `failed reads closer together than the retry spacing do not use up the attempts`() = runTest {
        addFolder(file("a.flac"), file("b.flac"))
        neverReadable += "b.flac"
        service.scan()
        reads.clear()

        repeat(5) {
            now += ReadRetry.RETRY_SPACING_MS / 10
            assertThat(newService().scan()).isEqualTo(ScanSummary(unchanged = 1, skipped = 1))
        }

        assertThat(reads).isEmpty()
        assertThat(indexedNames()).containsExactly("a.flac")
        now += ReadRetry.RETRY_SPACING_MS
        neverReadable -= "b.flac"
        assertThat(service.scan()).isEqualTo(ScanSummary(added = 1, unchanged = 1))
        assertThat(dao.allTracks().single { it.fileName == "b.flac" }.tagTitle).isEqualTo("b.flac")
    }

    @Test
    fun `an untagged fallback is read again once the re-read spacing passed and gets its tags`() = runTest {
        addFolder(file("a.flac"), file("b.flac"))
        giveUpOn("b.flac")
        neverReadable -= "b.flac"
        reads.clear()

        assertThat(service.scan()).isEqualTo(ScanSummary(unchanged = 2))
        assertThat(reads).isEmpty()

        now += ReadRetry.REREAD_SPACING_MS
        assertThat(newService().scan()).isEqualTo(ScanSummary(unchanged = 2))

        assertThat(reads).containsExactly("b.flac")
        assertThat(dao.allTracks().single { it.fileName == "b.flac" }.tagTitle).isEqualTo("b.flac")
        reads.clear()
        now += ReadRetry.REREAD_SPACING_MS
        service.scan()
        assertThat(reads).isEmpty()
    }

    @Test
    fun `re-reads of an untagged fallback are bounded and keep the fallback entry`() = runTest {
        addFolder(file("b.flac"))
        giveUpOn("b.flac")

        repeat(ReadRetry.MAX_REREADS) {
            reads.clear()
            now += ReadRetry.REREAD_SPACING_MS
            assertThat(service.scan()).isEqualTo(ScanSummary(unchanged = 1))
            assertThat(reads).containsExactly("b.flac")
        }
        reads.clear()
        now += 10 * ReadRetry.REREAD_SPACING_MS
        service.scan()

        assertThat(reads).isEmpty()
        assertThat(indexedNames()).containsExactly("b.flac")
        assertThat(dao.allTracks().single().title).isNotEmpty()
    }

    @Test
    fun `a failed artwork read of an untagged fallback does not count as one of its re-reads`() = runTest {
        addFolder(file("b.flac"))
        giveUpOn("b.flac")
        val givenUp = dao.readFailures(folder.treeUri).single()
        // The entry points at a cached picture that is gone, so every scan reads the file for its artwork.
        dao.updateTracks(listOf(dao.allTracks().single().copy(embeddedArtworkUri = "file:///gone/art.jpg")))
        reads.clear()
        now = givenUp.lastFailureMs + ReadRetry.REREAD_SPACING_MS / 2

        assertThat(service.scan()).isEqualTo(ScanSummary(unchanged = 1, skipped = 1))

        assertThat(reads).containsExactly("b.flac")
        assertThat(dao.readFailures(folder.treeUri).single()).isEqualTo(givenUp)
        now = givenUp.lastFailureMs + ReadRetry.REREAD_SPACING_MS
        service.scan()
        val reread = dao.readFailures(folder.treeUri).single()
        assertThat(reread.failures).isEqualTo(givenUp.failures + 1)
        assertThat(reread.lastFailureMs).isEqualTo(now)
    }

    @Test
    fun `a fallback file that changes is read again at once`() = runTest {
        addFolder(file("b.flac"))
        giveUpOn("b.flac")
        neverReadable -= "b.flac"
        reads.clear()
        listings[folder.treeUri] = listOf(file("b.flac", size = 300))

        assertThat(service.scan()).isEqualTo(ScanSummary(changed = 1))

        assertThat(reads).containsExactly("b.flac")
        assertThat(dao.allTracks().single().tagTitle).isEqualTo("b.flac")
    }

    @Test
    fun `tracks indexed before technical details were stored are read once more, then left alone`() = runTest {
        addFolder(file("a.flac"), file("b.flac"))
        service.scan()
        dao.updateTracks(dao.allTracks().map { it.copy(detailsVersion = 0) })
        reads.clear()

        val summary = service.scan()

        assertThat(reads).containsExactly("a.flac", "b.flac")
        assertThat(summary).isEqualTo(ScanSummary(unchanged = 2))
        assertThat(dao.allTracks().map { it.detailsVersion }.toSet()).containsExactly(TrackEntity.DETAILS_VERSION)
        reads.clear()
        service.scan()
        assertThat(reads).isEmpty()
    }

    @Test
    fun `an outdated track that cannot be read now keeps its entry and is not reported as skipped`() = runTest {
        addFolder(file("a.flac"))
        service.scan()
        dao.updateTracks(dao.allTracks().map { it.copy(detailsVersion = 0) })
        unreadable += "a.flac"

        val summary = service.scan()

        assertThat(summary).isEqualTo(ScanSummary(unchanged = 1))
        assertThat(dao.allTracks().single().detailsVersion).isEqualTo(0)
        assertThat(dao.readFailures(folder.treeUri)).isEmpty()
        service.scan()
        assertThat(dao.allTracks().single().detailsVersion).isEqualTo(TrackEntity.DETAILS_VERSION)
    }
}
