package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.playlist.ObservePlaylists
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

@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesViewModelTest {

    private val tracks = listOf(libraryTrack(1, album = 10), libraryTrack(2, album = 10), libraryTrack(3, album = 20))
    private val library = FakeTrackLibrary(tracks, listOf(album(10, artist = 1), album(20, artist = 2)))
    private val marks = FakeLibraryMarks()
    private val playback = RecordingPlaybackController()
    private val playlists = FakePlaylistRepository()
    private val mix = runBlocking { playlists.create("Mix", "") }
    private val other = runBlocking { playlists.create("Other", "") }

    private fun viewModel() = FavouritesViewModel(library, marks, playback, ObservePlaylists(playlists, library, EmptyCoroutineContext))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `favourite songs, albums and playlists are listed in favourites order, missing ones left out`() = runTest {
        marks.favourites.value = listOf(
            ItemRef.song(tracks[2]),
            ItemRef.album(20),
            ItemRef.song("content://gone"),
            ItemRef.song(tracks[0]),
            ItemRef.playlist(other),
        )
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        val state = vm.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.songs.map { it.id }).containsExactly(3L, 1L).inOrder()
        assertThat(state.albums.map { it.id }).containsExactly(20L)
        assertThat(state.playlists.map { it.playlist.id }).containsExactly(other)
    }

    @Test
    fun `a favourite playlist removed from Sonora is not listed`() = runTest {
        marks.favourites.value = listOf(ItemRef.playlist(mix), ItemRef.playlist(other))
        marks.hidden.value = listOf(HiddenItem(ItemRef.playlist(mix), "Mix", "", 1))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        assertThat(vm.state.value.playlists.map { it.playlist.id }).containsExactly(other)

        marks.hidden.value = emptyList()
        assertThat(vm.state.value.playlists.map { it.playlist.id }).containsExactly(mix, other).inOrder()
    }

    @Test
    fun `play and shuffle play the favourite songs as listed`() = runTest {
        marks.favourites.value = listOf(ItemRef.song(tracks[1]), ItemRef.song(tracks[2]))
        val vm = viewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

        vm.play(0)
        vm.shuffle()

        assertThat(playback.calls).containsExactly("playQueue:2,3@0", "playQueue:2,3@0+shuffle").inOrder()
    }
}
