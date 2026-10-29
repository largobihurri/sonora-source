package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.PendingSource
import io.sonora.core.domain.model.PickedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class LibraryPickerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val tracks = (1L..4L).map { libraryTrack(it, album = 10) }
    private val pending = PendingConversionSources()
    private lateinit var viewModel: LibraryPickerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = LibraryPickerViewModel(FakeTrackLibrary(tracks), pending)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val selected get() = viewModel.selection.value

    @Test
    fun `tapping a song selects it, tapping it again deselects it, in the order picked`() {
        viewModel.toggle(tracks[2])
        viewModel.toggle(tracks[0])
        assertThat(selected.tracks).containsExactly(tracks[2], tracks[0]).inOrder()
        assertThat(selected.count).isEqualTo(2)
        assertThat(selected.isSelected(tracks[0])).isTrue()

        viewModel.toggle(tracks[2])
        assertThat(selected.tracks).containsExactly(tracks[0])
        assertThat(selected.isSelected(tracks[2])).isFalse()
    }

    @Test
    fun `select all picks every song of the list, and deselects them once all are picked`() {
        viewModel.toggle(tracks[3])
        val album = tracks.take(3)

        viewModel.toggleAll(album)
        assertThat(selected.tracks).containsExactly(tracks[3], tracks[0], tracks[1], tracks[2]).inOrder()
        assertThat(selected.allSelected(album)).isTrue()

        viewModel.toggleAll(album)
        assertThat(selected.tracks).containsExactly(tracks[3])
        assertThat(selected.allSelected(emptyList())).isFalse()
    }

    @Test
    fun `add sends the picked songs to the converter and starts over`() {
        viewModel.toggle(tracks[1])
        viewModel.toggle(tracks[0])

        assertThat(viewModel.add()).isTrue()

        assertThat(pending.sources.value.map { it.pick }).containsExactly(
            PickedSource.Track(tracks[1].uri), PickedSource.Track(tracks[0].uri),
        ).inOrder()
        assertThat(pending.sources.value.first()).isEqualTo(PendingSource(PickedSource.Track(tracks[1].uri), "Song 2", "Artist 1"))
        assertThat(selected.count).isEqualTo(0)
    }

    @Test
    fun `cancelling forgets the picks and sends nothing`() {
        viewModel.toggle(tracks[1])

        viewModel.clearSelection()

        assertThat(selected.count).isEqualTo(0)
        assertThat(pending.sources.value).isEmpty()
    }

    @Test
    fun `add with nothing picked does nothing`() {
        assertThat(viewModel.add()).isFalse()
        assertThat(pending.sources.value).isEmpty()
    }

    @Test
    fun `search finds songs through the library search, and a blank query finds none`() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { viewModel.results.collect {} }

        viewModel.onQueryChange("Song 3")
        assertThat(viewModel.results.value).containsExactly(tracks[2])

        viewModel.onQueryChange("  ")
        assertThat(viewModel.results.value).isEmpty()
    }
}
