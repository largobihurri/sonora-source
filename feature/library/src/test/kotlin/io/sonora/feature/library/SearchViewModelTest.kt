package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.port.SearchHistoryRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class SearchViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val tracks = (1L..3L).map { libraryTrack(it) }
    private val playback = RecordingPlaybackController()
    private val history = FakeSearchHistory()
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SearchViewModel(FakeTrackLibrary(tracks), playback, history)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `submitting a search records it`() {
        viewModel.onQueryChange("song 2")
        viewModel.onSubmit()

        assertThat(history.recorded).containsExactly("song 2")
    }

    @Test
    fun `submitting a blank search records nothing`() {
        viewModel.onQueryChange("   ")
        viewModel.onSubmit()

        assertThat(history.recorded).isEmpty()
    }

    @Test
    fun `typing alone records nothing`() {
        viewModel.onQueryChange("so")
        viewModel.onQueryChange("song")

        assertThat(history.recorded).isEmpty()
    }

    @Test
    fun `opening a result records the search it came from and plays it`() = runTest(dispatcher) {
        val results = backgroundScope.launch { viewModel.results.collect {} }
        viewModel.onQueryChange("song")

        viewModel.play(viewModel.results.value, 1)

        assertThat(history.recorded).containsExactly("song")
        assertThat(playback.calls).containsExactly("playQueue:1,2,3@1")
        results.cancel()
    }

    @Test
    fun `choosing a recent search searches for it again and moves it first`() {
        history.recorded += listOf("song 3", "song 1")

        viewModel.searchAgain("song 1")

        assertThat(viewModel.query.value).isEqualTo("song 1")
        assertThat(history.recorded).containsExactly("song 1", "song 3").inOrder()
    }

    @Test
    fun `recent searches follow the store, and remove and clear change it`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.recentSearches.collect {} }
        history.recorded += listOf("abba", "queen", "muse")
        assertThat(viewModel.recentSearches.value).containsExactly("abba", "queen", "muse").inOrder()

        viewModel.removeRecentSearch("queen")
        assertThat(viewModel.recentSearches.value).containsExactly("abba", "muse").inOrder()

        viewModel.clearRecentSearches()
        assertThat(viewModel.recentSearches.value).isEmpty()
    }

    @Test
    fun `a search history that cannot be written changes nothing else`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.results.collect {} }
        history.failWrites = true
        viewModel.onQueryChange("song")

        viewModel.onSubmit()
        viewModel.searchAgain("song 1")
        viewModel.removeRecentSearch("song")
        viewModel.clearRecentSearches()
        viewModel.play(viewModel.results.value, 0)

        assertThat(history.recorded).isEmpty()
        assertThat(playback.calls).containsExactly("playQueue:1@0")
    }
}

/** Recent searches in memory, most recent first, unique ignoring case (the store's rules, without its cap). */
private class FakeSearchHistory : SearchHistoryRepository {
    private val state = MutableStateFlow<List<String>>(emptyList())
    override val recent: StateFlow<List<String>> = state

    /** The stored entries; tests may seed them. */
    var recorded: List<String>
        get() = state.value
        set(value) {
            state.value = value
        }

    /** Every write fails as a full disk would. */
    var failWrites = false

    override suspend fun record(query: String) {
        if (failWrites) throw IOException("disk full")
        state.value = listOf(query) + state.value.filterNot { it.equals(query, ignoreCase = true) }
    }

    override suspend fun remove(query: String) {
        if (failWrites) throw IOException("disk full")
        state.value = state.value.filterNot { it.equals(query, ignoreCase = true) }
    }

    override suspend fun clear() {
        if (failWrites) throw IOException("disk full")
        state.value = emptyList()
    }
}
