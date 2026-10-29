package io.sonora.player.ui

import io.sonora.core.domain.library.duplicates.ContentHasher
import io.sonora.core.domain.library.duplicates.DeleteDuplicateCopies
import io.sonora.core.domain.library.duplicates.DuplicateCopy
import io.sonora.core.domain.library.duplicates.DuplicateDismissals
import io.sonora.core.domain.library.duplicates.DuplicateFingerprint
import io.sonora.core.domain.library.duplicates.DuplicateFingerprints
import io.sonora.core.domain.library.duplicates.DuplicateGroup
import io.sonora.core.domain.library.duplicates.DuplicateLibrary
import io.sonora.core.domain.library.duplicates.DuplicateScan
import io.sonora.core.domain.library.duplicates.DuplicateScanState
import io.sonora.core.domain.library.duplicates.FindDuplicates
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.library.removal.DeleteFromPhone
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.port.PlaylistRepository
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuplicateNoticeTest {

    private fun copy(name: String, sizeBytes: Long) = DuplicateCopy(
        uri = "content://music/$name", title = "Song", artist = "Band", tagTitle = "Song", tagArtist = "Band", durationMs = 200_000,
        sizeBytes = sizeBytes, lastModifiedMs = 1, format = "FLAC", bitrateKbps = 900, sampleRateHz = 44_100, bitsPerSample = 16,
        channelCount = 2, folderPath = "Music", fileName = "$name.flac", hasArtwork = true, tagsFilled = 7, dateAddedSec = 1,
    )

    @Test
    fun `nothing shows unless the first analysis is announced`() {
        assertNull(DuplicateNotice.of(DuplicateScanState.Scanning(1, 2), announced = false))
        assertNull(DuplicateNotice.of(DuplicateScanState.Found(emptyList()), announced = false))
    }

    @Test
    fun `an announced analysis shows its progress, then how many groups it found`() {
        assertEquals(DuplicateNotice.Running(0, 0), DuplicateNotice.of(DuplicateScanState.NotScanned, announced = true))
        assertEquals(DuplicateNotice.Running(340, 1_200), DuplicateNotice.of(DuplicateScanState.Scanning(340, 1_200), announced = true))
        val group = DuplicateGroup(listOf(copy("a", 1), copy("b", 2)))
        assertEquals(DuplicateNotice.Finished(1), DuplicateNotice.of(DuplicateScanState.Found(listOf(group)), announced = true))
        assertNull(DuplicateNotice.of(DuplicateScanState.Failed, announced = true))
    }

    /** Hashes wait for [gate], so the analysis can be seen running. */
    private class GatedHasher : ContentHasher {
        val gate = CompletableDeferred<Unit>()

        override suspend fun hash(uri: String): String? {
            gate.await()
            return "same"
        }
    }

    @Test
    fun `the view model follows the first analysis to its end, and closing only hides the notice`() = runBlocking<Unit> {
        val library = object : DuplicateLibrary {
            override suspend fun copies() = listOf(copy("a", 5), copy("b", 5))
        }
        val hasher = GatedHasher()
        val scan = DuplicateScan(
            FindDuplicates(library, hasher, NoFingerprints, NoDismissals),
            library,
            NoDismissals,
            DeleteDuplicateCopies(DeleteFromPhone(NoFiles, NoPlaylists, NoMarks)),
            CoroutineScope(Job()),
        )
        val viewModel = DuplicateNoticeViewModel(scan)
        assertNull(viewModel.notice.first())
        scan.scan(announce = true)
        withTimeout(5_000) { viewModel.notice.first { it is DuplicateNotice.Running && it.total == 2 } }
        hasher.gate.complete(Unit)
        assertEquals(DuplicateNotice.Finished(1), withTimeout(5_000) { viewModel.notice.first { it is DuplicateNotice.Finished } })
        viewModel.close()
        assertNull(viewModel.notice.first())
        assertEquals(1, (scan.state.value as DuplicateScanState.Found).groups.size)
    }

    private object NoFingerprints : DuplicateFingerprints {
        override suspend fun all(): List<DuplicateFingerprint> = emptyList()

        override suspend fun update(fingerprints: List<DuplicateFingerprint>, gone: Collection<String>) = Unit
    }

    private object NoDismissals : DuplicateDismissals {
        override suspend fun dismissed(): List<Set<String>> = emptyList()

        override suspend fun dismiss(uris: Set<String>) = Unit
    }

    private object NoFiles : LibraryFileDeleter {
        override suspend fun writeAccessFor(uri: String): WriteAccessRequest? = null

        override suspend fun grantWriteAccess(request: WriteAccessRequest, pickedTreeUri: String) = WriteGrant.GRANTED

        override suspend fun delete(uri: String) = false
    }

    private object NoPlaylists : PlaylistRepository {
        override fun playlists(): Flow<List<PlaylistWithEntries>> = flowOf(emptyList())

        override fun playlist(id: Long): Flow<PlaylistWithEntries?> = flowOf(null)

        override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>) = 0L

        override suspend fun updateDetails(id: Long, name: String, description: String) = Unit

        override suspend fun delete(id: Long) = Unit

        override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = Unit
    }

    private object NoMarks : LibraryMarksRepository {
        override fun favourites(kind: MarkKind): Flow<List<String>> = flowOf(emptyList())

        override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) = Unit

        override fun pins(context: PinContext): Flow<List<String>> = flowOf(emptyList())

        override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) = Unit

        override fun hidden(): Flow<List<HiddenItem>> = flowOf(emptyList())

        override suspend fun hide(item: HiddenItem) = Unit

        override suspend fun restore(ref: ItemRef) = Unit

        override suspend fun forgetSongs(uris: Collection<String>) = Unit
    }
}
