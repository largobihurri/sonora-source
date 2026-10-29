package io.sonora.feature.library

import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.FolderContents
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.sort.SortDirection
import io.sonora.core.domain.sort.SortKey
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class FolderViewModelTest {

    private val music = FolderRef("content://tree/music", "Music")
    private val rock = music.child("Rock")
    private val empty = music.child("Empty")

    private fun track(id: Long, title: String = "T$id", durationMs: Long = 1_000) = Track(
        id = id, uri = "content://doc/$id", title = title, artist = "A", album = "B", albumArtist = null,
        durationMs = durationMs, trackNumber = null, discNumber = null, year = null, albumId = 5, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    /** Every track under [rock], in the browser's play order (subfolders included). */
    private val rockTracks = listOf(track(3), track(1), track(2))

    private class FakeBrowser(
        private val under: Map<FolderRef, List<Track>>,
        private val shown: Map<FolderRef, FolderContents> = emptyMap(),
    ) : FolderBrowser {
        val asked = mutableListOf<FolderRef>()
        var failure: Exception? = null
        override fun roots(): Flow<List<FolderEntry>> = flowOf(listOf(FolderEntry(FolderRef("content://tree/music", "Music"), 3)))
        override fun contents(folder: FolderRef): Flow<FolderContents> = shown[folder]?.let { flowOf(it) } ?: emptyFlow()
        override suspend fun tracksUnder(folder: FolderRef): List<Track> {
            asked += folder
            failure?.let { throw it }
            return under[folder].orEmpty()
        }
    }

    /** Records queue calls as "operation:track ids". */
    private class RecordingPlayback : PlaybackController {
        val calls = mutableListOf<String>()
        private fun record(op: String, tracks: List<Track>) {
            calls += "$op:" + tracks.joinToString(",") { it.id.toString() }
        }
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) =
            record(if (shuffle) "shuffle" else "play@$startIndex", queue)
        override fun playNow(tracks: List<Track>) = record("playNow", tracks)
        override fun playNext(tracks: List<Track>) = record("playNext", tracks)
        override fun addToQueue(tracks: List<Track>) = record("addToQueue", tracks)
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

    /** Rock's own screen: two subfolders and two songs, as the browser lists them. */
    private val rockContents = FolderContents(
        rock,
        listOf(FolderEntry(rock.child("Live"), 1), FolderEntry(rock.child("b-sides"), 1)),
        listOf(track(1, "zebra", durationMs = 5_000), track(2, "Apple", durationMs = 9_000)),
    )
    private val browser = FakeBrowser(mapOf(rock to rockTracks), mapOf(rock to rockContents))
    private val sortStore = FakeSortStore()
    private val marks = FakeLibraryMarks()
    private val playback = RecordingPlayback()
    private lateinit var viewModel: FolderViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = FolderViewModel(browser, playback, testSorting(sortStore), marks)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `play plays every track of the folder and its subfolders from the first one`() {
        viewModel.play(rock)

        assertThat(browser.asked).containsExactly(rock)
        assertThat(playback.calls).containsExactly("play@0:3,1,2")
    }

    @Test
    fun `shuffle shuffles every track of the folder and its subfolders`() {
        viewModel.shuffle(rock)

        assertThat(playback.calls).containsExactly("shuffle:3,1,2")
    }

    @Test
    fun `folder menu actions queue every track of the folder in folder order`() {
        viewModel.enqueue(rock, QueueAction.PLAY_NOW)
        viewModel.enqueue(rock, QueueAction.PLAY_NEXT)
        viewModel.enqueue(rock, QueueAction.ADD_TO_QUEUE)

        assertThat(playback.calls).containsExactly("playNow:3,1,2", "playNext:3,1,2", "addToQueue:3,1,2").inOrder()
    }

    @Test
    fun `a folder without tracks plays and queues nothing`() {
        viewModel.play(empty)
        viewModel.shuffle(empty)
        viewModel.enqueue(empty, QueueAction.PLAY_NEXT)

        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `a folder whose tracks cannot be loaded plays nothing and the app keeps running`() = runTest {
        // runTest fails the test if a coroutine outside it (here viewModelScope) ends with an uncaught exception.
        browser.failure = IOException("database closed")

        viewModel.play(rock)
        viewModel.shuffle(rock)
        viewModel.enqueue(rock, QueueAction.PLAY_NEXT)

        assertThat(browser.asked).containsExactly(rock, rock, rock)
        assertThat(playback.calls).isEmpty()
        browser.failure = null
        viewModel.play(rock)
        assertThat(playback.calls).containsExactly("play@0:3,1,2")
    }

    @Test
    fun `tapping a song plays the folder's own songs from it`() {
        viewModel.playTracks(rockTracks, 2)

        assertThat(playback.calls).containsExactly("play@2:3,1,2")
    }

    @Test
    fun `a folder's contents follow the folder sort, folders first`() = runTest {
        val byName = viewModel.contents(rock).first()

        assertThat(byName.order).isEqualTo(SortList.FOLDER.default)
        assertThat(byName.items.contents.subfolders.map { it.folder.name }).containsExactly("b-sides", "Live").inOrder()
        assertThat(byName.items.contents.tracks.map { it.id }).containsExactly(2L, 1L).inOrder()

        val longestFirst = SortOrder(SortKey.DURATION, SortDirection.DESCENDING)
        viewModel.setSort(longestFirst)

        assertThat(sortStore.stored.value[SortTarget(SortList.FOLDER)]).isEqualTo(longestFirst)
        val byDuration = viewModel.contents(rock).first()
        assertThat(byDuration.order).isEqualTo(longestFirst)
        assertThat(byDuration.items.contents.tracks.map { it.id }).containsExactly(2L, 1L).inOrder()
        viewModel.setSort(SortOrder(SortKey.DURATION, SortDirection.ASCENDING))
        assertThat(viewModel.contents(rock).first().items.contents.tracks.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `a folder sort that cannot be saved is dropped without crashing`() = runTest {
        sortStore.failure = IOException("disk full")

        viewModel.setSort(SortOrder(SortKey.DURATION, SortDirection.DESCENDING))

        assertThat(sortStore.stored.value).isEmpty()
        assertThat(viewModel.contents(rock).first().order).isEqualTo(SortList.FOLDER.default)
    }

    @Test
    fun `pinned subfolders and songs come first in their folder only`() = runTest {
        marks.pins.value = listOf(
            PinContext.folder(rock) to ItemRef.folder(rock.child("Live")),
            PinContext.folder(rock) to ItemRef.song("content://doc/1"),
            PinContext.SONGS to ItemRef.song("content://doc/2"),
        )
        val listed = viewModel.contents(rock).first().items
        assertThat(listed.contents.subfolders.map { it.folder.name }).containsExactly("Live", "b-sides").inOrder()
        assertThat(listed.contents.tracks.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(listed.pinned).containsExactly(ItemRef.folder(rock.child("Live")).key, "content://doc/1")
    }
}
