package io.sonora.feature.library

import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.conversion.batch.ResolveConvertedTracks
import io.sonora.core.domain.library.ObserveConvertedMusic
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConvertedFile
import io.sonora.core.domain.port.ConvertedFileReader
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.sort.SortDirection
import io.sonora.core.domain.sort.SortKey
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class ConvertedMusicViewModelTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    /** The converter history: batches with their items; every output exists unless listed in [gone]. */
    private class History : ConversionBatchRepository {
        val batches = MutableStateFlow<List<Pair<ConversionBatch, List<ConversionBatchItem>>>>(emptyList())
        var failure: Exception? = null

        /** How many more reads of the history fail with [failure] (all of them by default). */
        var failuresLeft = Int.MAX_VALUE

        override fun batches(): Flow<List<ConversionBatch>> = flow {
            val error = failure?.takeIf { failuresLeft > 0 }
            if (error != null) {
                failuresLeft--
                throw error
            }
            emitAll(batches.map { list -> list.map { it.first }.sortedByDescending { it.createdAtMs } })
        }
        override suspend fun getBatch(id: Long) = batches.value.firstOrNull { it.first.id == id }?.first
        override suspend fun getItems(batchId: Long) = batches.value.firstOrNull { it.first.id == batchId }?.second.orEmpty()
        override suspend fun create(batch: ConversionBatch, sources: List<BatchSource>): Long = error("unused")
        override fun batch(id: Long): Flow<ConversionBatch?> = error("unused")
        override fun items(batchId: Long): Flow<List<ConversionBatchItem>> = error("unused")
        override suspend fun updateBatch(batch: ConversionBatch) = error("unused")
        override suspend fun updateItem(item: ConversionBatchItem) = error("unused")
        override suspend fun transition(batchId: Long, from: Set<BatchStatus>, to: BatchStatus, failureReason: String?) = error("unused")
        override suspend fun delete(batchId: Long) = error("unused")
    }

    private class RecordingPlayback : PlaybackController {
        val played = mutableListOf<String>()
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) {
            played += (if (shuffle) "shuffle:" else "$startIndex:") + queue.joinToString(",") { it.title }
        }
        override fun playNow(tracks: List<Track>) = error("unused")
        override fun playNext(tracks: List<Track>) = error("unused")
        override fun addToQueue(tracks: List<Track>) = error("unused")
        override fun moveQueueItem(from: Int, to: Int) = Unit
        override fun removeQueueItem(index: Int) = Unit
        override fun clearUpcoming() = Unit
        override fun skipToQueueItem(index: Int) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun togglePlayPause() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setShuffle(enabled: Boolean) = Unit
        override fun setRepeatMode(mode: RepeatMode) = Unit
        override fun cycleRepeatMode() = Unit
    }

    private val history = History()
    private val gone = mutableSetOf<String>()
    private val reader = ConvertedFileReader { uri, name ->
        if (uri in gone) null else ConvertedFile(TrackTags(title = name.substringBeforeLast('.')), null, "audio/aiff", 1)
    }
    private val playback = RecordingPlayback()
    private val sortStore = FakeSortStore()
    private val marks = FakeLibraryMarks()
    private lateinit var viewModel: ConvertedMusicViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Opens the screen; [virtualTime] runs the list's reading on the test's clock (for the retry delay). */
    private fun TestScope.open(virtualTime: Boolean = false): ConvertedMusicViewModel {
        viewModel = ConvertedMusicViewModel(
            ObserveConvertedMusic(history, ResolveConvertedTracks(history, reader), clock, dayChecks = emptyFlow()),
            playback,
            testSorting(sortStore, if (virtualTime) UnconfinedTestDispatcher(testScheduler) else Dispatchers.Unconfined),
            marks,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        return viewModel
    }

    /** Batch [id], [hoursAgo] old, whose [songs] were converted to "<song>.aiff". */
    private fun batch(id: Long, hoursAgo: Long, vararg songs: String) {
        val batch = ConversionBatch(
            id, "xdj-700", PerformanceMode.NORMAL, now.minusSeconds(hoursAgo * 3_600).toEpochMilli(), "folder $id", null,
            overwriteOriginals = false, status = BatchStatus.COMPLETED,
        )
        val items = songs.mapIndexed { position, song ->
            ConversionBatchItem(
                id * 100 + position, id, position, "content://src/$song.flac", "$song.flac", null,
                state = BatchItemState.REPLACED_ORIGINAL, outputUri = "content://out/$song", outputName = "$song.aiff",
                profileId = "xdj-700",
            )
        }
        history.batches.value += batch to items
    }

    private val state get() = viewModel.state.value

    @Test
    fun `lists the converted songs by date, newest first`() = runTest {
        batch(1, hoursAgo = 30, "Old")
        batch(2, hoursAgo = 1, "New 1", "New 2")

        open()

        assertThat(state.loading).isFalse()
        assertThat(state.order).isEqualTo(SortList.CONVERTED.default)
        assertThat(state.groups.map { it.section to it.tracks.map(Track::title) }).containsExactly(
            HistorySection.TODAY to listOf("New 1", "New 2"),
            HistorySection.YESTERDAY to listOf("Old"),
        ).inOrder()
        assertThat(state.tracks.map { it.title }).containsExactly("New 1", "New 2", "Old").inOrder()
    }

    @Test
    fun `songs whose file is gone are not listed`() = runTest {
        batch(1, hoursAgo = 1, "Kept", "Deleted")
        gone += "content://out/Deleted"

        open()

        assertThat(state.tracks.map { it.title }).containsExactly("Kept")
    }

    @Test
    fun `play plays the whole list from the top, shuffle shuffles it`() = runTest {
        batch(1, hoursAgo = 30, "Old")
        batch(2, hoursAgo = 1, "New")
        open()

        viewModel.play()
        viewModel.shuffle()

        assertThat(playback.played).containsExactly("0:New,Old", "shuffle:New,Old").inOrder()
    }

    @Test
    fun `a tapped song plays the list from that song`() = runTest {
        batch(1, hoursAgo = 30, "Old")
        batch(2, hoursAgo = 1, "New 1", "New 2")
        open()

        viewModel.play(state.tracks.first { it.title == "Old" })

        assertThat(playback.played).containsExactly("2:New 1,New 2,Old")
    }

    @Test
    fun `nothing converted yet shows the empty list and plays nothing`() = runTest {
        open()

        viewModel.play()
        viewModel.shuffle()

        assertThat(state.loading).isFalse()
        assertThat(state.groups).isEmpty()
        assertThat(playback.played).isEmpty()
    }

    @Test
    fun `a history that cannot be read shows an empty list`() = runTest {
        history.failure = IOException("disk")

        open()

        assertThat(state.loading).isFalse()
        assertThat(state.groups).isEmpty()
    }

    @Test
    fun `after a failed read the list comes back on its own`() = runTest {
        batch(1, hoursAgo = 1, "Back")
        history.failure = IOException("disk")
        history.failuresLeft = 1
        open(virtualTime = true)
        assertThat(state.loading).isFalse()
        assertThat(state.groups).isEmpty()

        advanceTimeBy(ConvertedMusicViewModel.RETRY_DELAY_MS + 1)

        assertThat(state.tracks.map { it.title }).containsExactly("Back")
        // And it keeps following the history.
        batch(2, hoursAgo = 0, "Newer")
        assertThat(state.tracks.map { it.title }).containsExactly("Newer", "Back").inOrder()
    }

    @Test
    fun `a sort that cannot be saved is dropped without crashing`() = runTest {
        batch(1, hoursAgo = 1, "a")
        open()
        sortStore.failure = IOException("disk full")

        viewModel.setSort(SortOrder(SortKey.TITLE))

        assertThat(sortStore.stored.value).isEmpty()
        assertThat(state.order).isEqualTo(SortList.CONVERTED.default)
    }

    @Test
    fun `oldest first reverses the date sections and plays in that order`() = runTest {
        batch(1, hoursAgo = 30, "Old")
        batch(2, hoursAgo = 1, "New 1", "New 2")
        open()

        viewModel.setSort(SortOrder(SortKey.DATE_CONVERTED, SortDirection.ASCENDING))

        assertThat(state.groups.map { it.section }).containsExactly(HistorySection.YESTERDAY, HistorySection.TODAY).inOrder()
        viewModel.play()
        assertThat(playback.played).containsExactly("0:Old,New 1,New 2")
    }

    @Test
    fun `sorting by title lists every song in one list without sections, and is remembered`() = runTest {
        batch(1, hoursAgo = 30, "b")
        batch(2, hoursAgo = 1, "C", "a")
        open()
        val byTitle = SortOrder(SortKey.TITLE)

        viewModel.setSort(byTitle)

        assertThat(state.groups.map { it.section to it.tracks.map(Track::title) }).containsExactly(null to listOf("a", "b", "C"))
        assertThat(sortStore.stored.value[SortTarget(SortList.CONVERTED)]).isEqualTo(byTitle)
        viewModel.play(state.tracks.first { it.title == "b" })
        assertThat(playback.played).containsExactly("1:a,b,C")
    }

    @Test
    fun `pinned songs form a first group above the date sections, hidden songs are not listed`() = runTest {
        batch(1, hoursAgo = 30, "Old", "Gone")
        batch(2, hoursAgo = 1, "New")
        marks.pins.value = listOf(PinContext.CONVERTED to ItemRef.song("content://out/Old"))
        marks.hidden.value = listOf(HiddenItem(ItemRef.song("content://out/Gone"), "Gone", "", 1))

        open()

        assertThat(state.groups.map { it.section to it.tracks.map(Track::title) }).containsExactly(
            null to listOf("Old"),
            HistorySection.TODAY to listOf("New"),
        ).inOrder()
        assertThat(state.pinned).containsExactly("content://out/Old")
    }
}
