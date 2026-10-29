package io.sonora.feature.library

import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.playlist.DeletePlaylist
import io.sonora.core.domain.playlist.EditPlaylistDetails
import io.sonora.core.domain.playlist.MovePlaylistEntry
import io.sonora.core.domain.playlist.ObservePlaylist
import io.sonora.core.domain.playlist.RemovePlaylistEntry
import io.sonora.core.domain.sort.SortDirection
import io.sonora.core.domain.sort.SortKey
import io.sonora.core.domain.sort.SortList
import io.sonora.core.domain.sort.SortOrder
import io.sonora.core.domain.sort.SortTarget
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class PlaylistDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakePlaylistRepository()
    private val playback = RecordingPlaybackController()
    private val sortStore = FakeSortStore()
    private val marks = FakeLibraryMarks()

    // Songs 1-3 are in the library; song 9's file is gone.
    private val library = FakeTrackLibrary(listOf(libraryTrack(1), libraryTrack(2), libraryTrack(3)))
    private val missing = PlaylistEntry(0, "content://music/9", "Song 9", "Artist 9")
    private var playlistId = 0L
    private lateinit var viewModel: PlaylistDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        playlistId = runBlocking {
            repository.create(
                "Mix",
                "",
                listOf(PlaylistEntry.of(libraryTrack(2)), missing, PlaylistEntry.of(libraryTrack(1)), PlaylistEntry.of(libraryTrack(2))),
            )
        }
        viewModel = PlaylistDetailViewModel(
            playlistId,
            ObservePlaylist(repository, library, EmptyCoroutineContext),
            EditPlaylistDetails(repository),
            DeletePlaylist(repository),
            RemovePlaylistEntry(repository),
            MovePlaylistEntry(repository),
            playback,
            testSorting(sortStore),
            marks,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun runCollecting(block: suspend () -> Unit) = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        block()
    }

    private fun entryIds() = repository.entries(playlistId).map { it.id }

    @Test
    fun `the playlist shows every entry, the missing one as unavailable`() = runCollecting {
        val playlist = viewModel.state.value.playlist!!

        assertThat(playlist.items.map { it.isAvailable }).containsExactly(true, false, true, true).inOrder()
        assertThat(playlist.items[1].entry.title).isEqualTo("Song 9")
        assertThat(viewModel.state.value.loading).isFalse()
    }

    @Test
    fun `play and shuffle skip unavailable entries`() = runCollecting {
        viewModel.play()
        viewModel.shuffle()

        assertThat(playback.calls).containsExactly("playQueue:2,1,2@0", "playQueue:2,1,2@0+shuffle").inOrder()
    }

    @Test
    fun `a tapped song plays the available songs from it, and an unavailable one does nothing`() = runCollecting {
        val items = viewModel.state.value.playlist!!.items

        viewModel.play(items[2].entry.id)
        viewModel.play(items[1].entry.id)

        assertThat(playback.calls).containsExactly("playQueue:2,1,2@1")
    }

    @Test
    fun `a playlist with nothing playable plays nothing`() = runCollecting {
        repository.editEntries(playlistId) { listOf(missing) }

        viewModel.play()
        viewModel.shuffle()

        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `edit mode toggles, and removing and moving entries are saved`() = runCollecting {
        assertThat(viewModel.state.value.editing).isFalse()
        viewModel.startEditing()
        assertThat(viewModel.state.value.editing).isTrue()
        val ids = entryIds()

        viewModel.move(ids[3], toIndex = 0)
        viewModel.remove(ids[1])

        assertThat(entryIds()).containsExactly(ids[3], ids[0], ids[2]).inOrder()
        assertThat(viewModel.state.value.playlist!!.items.map { it.entry.id }).containsExactly(ids[3], ids[0], ids[2]).inOrder()
        viewModel.finishEditing()
        assertThat(viewModel.state.value.editing).isFalse()
    }

    @Test
    fun `details are edited, and a blank name is ignored`() = runCollecting {
        viewModel.editDetails(" Party ", "Loud")
        viewModel.editDetails("  ", "ignored")

        val playlist = viewModel.state.value.playlist!!.playlist
        assertThat(playlist.name).isEqualTo("Party")
        assertThat(playlist.description).isEqualTo("Loud")
    }

    @Test
    fun `a deleted playlist reports itself gone`() = runCollecting {
        assertThat(viewModel.state.value.deleted).isFalse()

        viewModel.delete()

        assertThat(repository.state.value).isEmpty()
        assertThat(viewModel.state.value.deleted).isTrue()
        assertThat(viewModel.state.value.editing).isFalse()
    }

    private fun target() = SortTarget(SortList.PLAYLIST, playlistId)

    @Test
    fun `playlist order is the default and the only order that can be rearranged`() = runCollecting {
        val state = viewModel.state.value

        assertThat(state.order).isEqualTo(SortList.PLAYLIST.default)
        assertThat(state.canReorder).isTrue()
        assertThat(state.items).isEqualTo(state.playlist!!.items)
    }

    @Test
    fun `a sorted playlist lists, plays and taps its songs in that order, unavailable ones last`() = runCollecting {
        val byTitle = SortOrder(SortKey.TITLE, SortDirection.DESCENDING)
        val ids = entryIds()

        viewModel.setSort(byTitle)

        val state = viewModel.state.value
        assertThat(sortStore.stored.value[target()]).isEqualTo(byTitle)
        assertThat(state.order).isEqualTo(byTitle)
        assertThat(state.canReorder).isFalse()
        assertThat(state.items.map { it.entry.id }).containsExactly(ids[0], ids[3], ids[2], ids[1]).inOrder()
        // The playlist itself keeps its own order (and cover).
        assertThat(state.playlist!!.items.map { it.entry.id }).containsExactly(ids[0], ids[1], ids[2], ids[3]).inOrder()

        viewModel.play()
        viewModel.play(ids[2])
        assertThat(playback.calls).containsExactly("playQueue:2,2,1@0", "playQueue:2,2,1@2").inOrder()
    }

    @Test
    fun `each playlist remembers its own sort`() = runCollecting {
        sortStore.stored.value = mapOf(SortTarget(SortList.PLAYLIST, playlistId + 1) to SortOrder(SortKey.ARTIST))

        assertThat(viewModel.state.value.order).isEqualTo(SortList.PLAYLIST.default)
    }

    @Test
    fun `deleting a playlist forgets its sort`() = runCollecting {
        viewModel.setSort(SortOrder(SortKey.DURATION))

        viewModel.delete()

        assertThat(sortStore.stored.value).doesNotContainKey(target())
    }

    @Test
    fun `pinned songs come first, but Edit shows the playlist's own order`() = runCollecting {
        marks.pins.value = listOf(PinContext.playlist(playlistId) to ItemRef.song("content://music/1"))
        assertThat(viewModel.state.value.items.map { it.entry.trackUri })
            .containsExactly("content://music/1", "content://music/2", "content://music/9", "content://music/2").inOrder()
        assertThat(viewModel.state.value.pinned).containsExactly("content://music/1")

        viewModel.startEditing()
        assertThat(viewModel.state.value.items.map { it.entry.trackUri }.first()).isEqualTo("content://music/2")
        assertThat(viewModel.state.value.pinned).isEmpty()
    }
}
