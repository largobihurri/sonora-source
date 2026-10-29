package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.smart.ObserveSmartPlaylist
import io.sonora.core.domain.library.smart.SmartPlaylist
import io.sonora.core.domain.library.smart.SmartPlaylistRepository
import io.sonora.core.domain.model.Track
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmartPlaylistViewModelTest {

    private class FakeSmartPlaylists : SmartPlaylistRepository {
        val recentlyAdded = MutableStateFlow<List<Track>>(emptyList())
        val mostPlayed = MutableStateFlow<List<Track>>(emptyList())
        val neverPlayed = MutableStateFlow<List<Track>>(emptyList())
        override fun recentlyAdded(sinceSec: Long): Flow<List<Track>> = recentlyAdded
        override fun mostPlayed(limit: Int): Flow<List<Track>> = mostPlayed
        override fun neverPlayed(): Flow<List<Track>> = neverPlayed
    }

    private val repository = FakeSmartPlaylists()
    private val playback = RecordingPlaybackController()
    private val observe = ObserveSmartPlaylist(repository, Clock.fixed(Instant.ofEpochSecond(1_800_000_000), ZoneOffset.UTC))

    private fun viewModel(playlist: SmartPlaylist) = SmartPlaylistViewModel(playlist, observe, playback)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `each page lists its own playlist's songs in the playlist's order`() = runTest {
        repository.recentlyAdded.value = listOf(libraryTrack(1), libraryTrack(2))
        repository.mostPlayed.value = listOf(libraryTrack(3), libraryTrack(1))
        repository.neverPlayed.value = listOf(libraryTrack(4))
        val pages = SmartPlaylist.entries.associateWith(::viewModel)
        pages.values.forEach { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} } }

        assertThat(pages.mapValues { (_, vm) -> vm.state.value.songs.map { it.id } }).containsExactly(
            SmartPlaylist.RECENTLY_ADDED, listOf(1L, 2L),
            SmartPlaylist.MOST_PLAYED, listOf(3L, 1L),
            SmartPlaylist.NEVER_PLAYED, listOf(4L),
        )
        assertThat(pages.values.map { it.state.value.loading }).containsExactly(false, false, false)
        assertThat(pages.keys.map { pages.getValue(it).playlist }).containsExactlyElementsIn(SmartPlaylist.entries).inOrder()
    }

    @Test
    fun `the list follows the library live`() = runTest {
        val vm = viewModel(SmartPlaylist.MOST_PLAYED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertThat(vm.state.value.songs).isEmpty()

        repository.mostPlayed.value = listOf(libraryTrack(2), libraryTrack(1))
        assertThat(vm.state.value.songs.map { it.id }).containsExactly(2L, 1L).inOrder()

        repository.mostPlayed.value = listOf(libraryTrack(1))
        assertThat(vm.state.value.songs.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `play and shuffle play the songs as listed`() = runTest {
        repository.neverPlayed.value = listOf(libraryTrack(5), libraryTrack(6))
        val vm = viewModel(SmartPlaylist.NEVER_PLAYED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.play(1)
        vm.play(9)
        vm.shuffle()

        assertThat(playback.calls).containsExactly("playQueue:5,6@1", "playQueue:5,6@1", "playQueue:5,6@0+shuffle").inOrder()
    }

    @Test
    fun `an empty playlist plays nothing`() = runTest {
        val vm = viewModel(SmartPlaylist.RECENTLY_ADDED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.play(0)
        vm.shuffle()

        assertThat(playback.calls).isEmpty()
    }
}
