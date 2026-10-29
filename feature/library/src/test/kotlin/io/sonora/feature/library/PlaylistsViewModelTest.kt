package io.sonora.feature.library

import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.AddTracksToPlaylist
import io.sonora.core.domain.playlist.CreatePlaylist
import io.sonora.core.domain.playlist.ObservePlaylists
import io.sonora.core.domain.port.FolderBrowser
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class PlaylistsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakePlaylistRepository()
    private val marks = FakeLibraryMarks()
    private val tracks = listOf(
        libraryTrack(1, album = 10, artist = 1),
        libraryTrack(2, album = 10, artist = 2),
        libraryTrack(3, album = 20, artist = 1),
        libraryTrack(4, album = 30, artist = 3),
    )
    // Album 10 is a compilation (album artist 9) with songs by artists 1 and 2.
    private val library = FakeTrackLibrary(tracks, listOf(album(10, artist = 9), album(20, artist = 1), album(30, artist = 3)))
    private lateinit var viewModel: PlaylistsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = PlaylistsViewModel(
            ObservePlaylists(repository, library, EmptyCoroutineContext),
            CreatePlaylist(repository),
            AddTracksToPlaylist(repository),
            library,
            FakeFolderBrowser(listOf(tracks[3], tracks[0])),
            marks,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectEvents(): MutableList<PlaylistEvent> {
        val events = mutableListOf<PlaylistEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.toList(events) }
        return events
    }

    private fun uris(playlistId: Long) = repository.entries(playlistId).map { it.trackUri }

    @Test
    fun `playlists list by name with their song counts`() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        assertThat(viewModel.state.value.loading).isFalse()
        assertThat(viewModel.state.value.playlists).isEmpty()

        viewModel.create("Workout", "")
        viewModel.create("chill", "")

        assertThat(viewModel.state.value.playlists.map { it.playlist.name }).containsExactly("chill", "Workout").inOrder()
    }

    @Test
    fun `create stores a trimmed playlist and asks to open it`() = runTest(dispatcher) {
        val events = collectEvents()

        viewModel.create("  Road Trip ", " Summer ")

        val stored = repository.state.value.single().playlist
        assertThat(stored.name).isEqualTo("Road Trip")
        assertThat(stored.description).isEqualTo("Summer")
        assertThat(events).containsExactly(PlaylistEvent.Created(stored.id))
    }

    @Test
    fun `a blank name creates nothing`() = runTest(dispatcher) {
        val events = collectEvents()

        viewModel.create("   ", "x")
        viewModel.createAndAdd(" ", "", PlaylistSource.Tracks(listOf(tracks[0])))

        assertThat(repository.state.value).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun `adding songs appends them, duplicates included, and confirms with the playlist name`() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.create("Mix", "")
        val id = repository.state.value.single().playlist.id

        viewModel.addTo(id, "Mix", PlaylistSource.Tracks(listOf(tracks[1])))
        viewModel.addTo(id, "Mix", PlaylistSource.Tracks(listOf(tracks[1])))

        assertThat(uris(id)).containsExactly("content://music/2", "content://music/2")
        assertThat(events.drop(1)).containsExactly(PlaylistEvent.Added("Mix"), PlaylistEvent.Added("Mix"))
    }

    @Test
    fun `album, artist and folder sources add all their songs`() = runTest(dispatcher) {
        viewModel.create("Mix", "")
        val id = repository.state.value.single().playlist.id

        viewModel.addTo(id, "Mix", PlaylistSource.Album(10))
        assertThat(uris(id)).containsExactly("content://music/1", "content://music/2").inOrder()

        repository.editEntries(id) { emptyList() }
        // Artist 1: their song on the compilation (not artist 2's) and their album 20.
        viewModel.addTo(id, "Mix", PlaylistSource.Artist(1))
        assertThat(uris(id)).containsExactly("content://music/1", "content://music/3").inOrder()

        repository.editEntries(id) { emptyList() }
        viewModel.addTo(id, "Mix", PlaylistSource.Folder(FolderRef("content://tree/music", "Music")))
        assertThat(uris(id)).containsExactly("content://music/4", "content://music/1").inOrder()
    }

    @Test
    fun `new playlist from the picker creates it with the songs in one step`() = runTest(dispatcher) {
        val events = collectEvents()

        viewModel.createAndAdd(" Faves ", "", PlaylistSource.Album(20))

        val stored = repository.state.value.single()
        assertThat(stored.playlist.name).isEqualTo("Faves")
        assertThat(stored.entries.map { it.trackUri }).containsExactly("content://music/3")
        assertThat(events).containsExactly(PlaylistEvent.Added("Faves"))
    }

    @Test
    fun `a source without songs adds nothing and says so`() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.create("Mix", "")
        val id = repository.state.value.single().playlist.id

        viewModel.addTo(id, "Mix", PlaylistSource.Album(99))
        viewModel.createAndAdd("Empty", "", PlaylistSource.Tracks(emptyList()))

        assertThat(repository.entries(id)).isEmpty()
        assertThat(repository.state.value).hasSize(1)
        assertThat(events).containsExactly(
            PlaylistEvent.Created(id), PlaylistEvent.NothingToAdd, PlaylistEvent.NothingToAdd,
        ).inOrder()
    }

    @Test
    fun `songs that cannot be read are reported, never dropped silently`() = runTest(dispatcher) {
        val unreadable = object : FolderBrowser by FakeFolderBrowser(tracks) {
            override suspend fun tracksUnder(folder: FolderRef): List<Track> = throw IOException("index gone")
        }
        viewModel = PlaylistsViewModel(
            ObservePlaylists(repository, library, EmptyCoroutineContext),
            CreatePlaylist(repository),
            AddTracksToPlaylist(repository),
            library,
            unreadable,
            marks,
        )
        val events = collectEvents()
        viewModel.create("Mix", "")
        val id = repository.state.value.single().playlist.id
        val folder = PlaylistSource.Folder(FolderRef("content://tree/music", "Music"))

        viewModel.addTo(id, "Mix", folder)
        viewModel.createAndAdd("Faves", "", folder)

        assertThat(repository.state.value.map { it.playlist.name }).containsExactly("Mix")
        assertThat(events).containsExactly(PlaylistEvent.Created(id), PlaylistEvent.AddFailed, PlaylistEvent.AddFailed).inOrder()
    }

    @Test
    fun `pinned playlists come first and playlists removed from Sonora are not listed`() = runTest(dispatcher) {
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        val a = repository.create("Alpha", "")
        val b = repository.create("Beta", "")
        val c = repository.create("Gamma", "")
        marks.pins.value = listOf(PinContext.PLAYLISTS to ItemRef.playlist(c))
        marks.hidden.value = listOf(HiddenItem(ItemRef.playlist(b), "Beta", "", 1))

        assertThat(viewModel.state.value.playlists.map { it.playlist.id }).containsExactly(c, a).inOrder()
        assertThat(viewModel.state.value.pinned).containsExactly(c.toString())
    }
}
