package io.sonora.feature.library

import kotlinx.coroutines.flow.first
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.PinContext
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.playback.PlaybackBandLevels
import io.sonora.core.domain.port.CoverFlowPreferencesRepository
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.LibraryScanner
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class LibraryViewModelTest {

    private val music = LibraryFolder("content://tree/music", "Music", 1)

    /** Answers every add with [result], or throws [failure] when set. */
    private class FakeFolders(var result: AddFolderResult, var failure: Exception? = null) : LibraryFolderRepository {
        override fun folders(): Flow<List<LibraryFolder>> = flowOf(emptyList())
        override suspend fun add(treeUri: String): AddFolderResult = failure?.let { throw it } ?: result
        override suspend fun remove(treeUri: String) = Unit
    }

    /** A library of [tracks], [albums], [artists] and [albumTracks]. */
    private class FakeLibrary(
        private val albumTracks: Map<Long, List<Track>> = emptyMap(),
        private val tracks: List<Track> = emptyList(),
        private val albums: List<Album> = emptyList(),
        private val artists: List<Artist> = emptyList(),
    ) : LibraryRepository {
        override fun tracks(sort: TrackSort): Flow<List<Track>> = flowOf(tracks)
        override fun albums(): Flow<List<Album>> = flowOf(albums)
        override fun artists(): Flow<List<Artist>> = flowOf(artists)
        override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = flowOf(emptyList())
        override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = flowOf(albumTracks[albumId].orEmpty())
        override fun albumsForArtist(artistId: Long): Flow<List<Album>> = flowOf(emptyList())
        override fun search(query: String): Flow<List<Track>> = flowOf(emptyList())
    }

    /** The Cover Flow settings, held in memory. */
    private class FakeCoverFlow : CoverFlowPreferencesRepository {
        val stored = MutableStateFlow(false)
        override val hideAlbumsWithoutArtwork: Flow<Boolean> = stored
        override suspend fun setHideAlbumsWithoutArtwork(hide: Boolean) {
            stored.value = hide
        }
    }

    private object IdleScanner : LibraryScanner {
        override val progress: StateFlow<ScanProgress> = MutableStateFlow(ScanProgress.Idle)
        override fun requestScan() = Unit
    }

    /** Records queue calls as "operation:track ids". */
    private class RecordingPlayback : PlaybackController {
        val calls = mutableListOf<String>()
        private fun record(op: String, tracks: List<Track>) {
            calls += "$op:" + tracks.joinToString(",") { it.id.toString() }
        }
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) = record("playQueue", queue)
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

    private fun track(id: Long, disc: Int?, number: Int?, title: String = "T$id", added: Long = 0) = Track(
        id = id, uri = "content://doc/$id", title = title, dateAddedSec = added, artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = number, discNumber = disc, year = null, albumId = 5, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    // The repository already returns album tracks in disc + track order.
    private val albumTracks = listOf(track(3, 1, 1), track(1, 1, 2), track(2, 2, 1))
    private val folders = FakeFolders(AddFolderResult.Added(music))
    private val playback = RecordingPlayback()
    private val sortStore = FakeSortStore()
    private val marks = FakeLibraryMarks()
    private val coverFlow = FakeCoverFlow()
    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = LibraryViewModel(FakeLibrary(mapOf(5L to albumTracks)), folders, IdleScanner, playback, testSorting(sortStore), marks, coverFlow, NoBandLevels)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an added folder reports no failure`() {
        viewModel.addFolder(music.treeUri)

        assertThat(viewModel.addFailed.value).isFalse()
    }

    @Test
    fun `a rejected folder reports a failure`() {
        folders.result = AddFolderResult.Rejected(FolderConflict.AlreadyAdded(music))

        viewModel.addFolder(music.treeUri)

        assertThat(viewModel.addFailed.value).isTrue()
    }

    @Test
    fun `a folder that cannot be added reports a failure until dismissed, and a later add clears it`() {
        folders.failure = SecurityException("no persistable grant")

        viewModel.addFolder(music.treeUri)

        assertThat(viewModel.addFailed.value).isTrue()
        viewModel.dismissAddFailure()
        assertThat(viewModel.addFailed.value).isFalse()

        viewModel.addFolder(music.treeUri)
        assertThat(viewModel.addFailed.value).isTrue()
        folders.failure = null
        viewModel.addFolder(music.treeUri)
        assertThat(viewModel.addFailed.value).isFalse()
    }

    @Test
    fun `album actions queue the album's tracks in album order`() {
        viewModel.enqueueAlbum(5, QueueAction.PLAY_NEXT)
        viewModel.enqueueAlbum(5, QueueAction.ADD_TO_QUEUE)
        viewModel.enqueueAlbum(5, QueueAction.PLAY_NOW)

        assertThat(playback.calls).containsExactly("playNext:3,1,2", "addToQueue:3,1,2", "playNow:3,1,2").inOrder()
    }

    @Test
    fun `an empty album queues nothing`() {
        viewModel.enqueueAlbum(99, QueueAction.PLAY_NEXT)

        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `song actions queue that song`() {
        viewModel.enqueue(listOf(albumTracks[1]), QueueAction.ADD_TO_QUEUE)

        assertThat(playback.calls).containsExactly("addToQueue:1")
    }

    private fun album(id: Long, title: String, added: Long) =
        Album(id = id, title = title, artist = "A", year = null, trackCount = 1, artworkUri = null, dateAddedSec = added)

    private fun TestScope.sortedLibrary(): LibraryViewModel {
        val library = FakeLibrary(
            tracks = listOf(track(1, null, null, title = "beta", added = 10), track(2, null, null, title = "Álpha", added = 30)),
            albums = listOf(album(1, "Zed", added = 5), album(2, "Abc", added = 1)),
            artists = listOf(Artist(1, "Queen", 1, 1, dateAddedSec = 1), Artist(2, "Abba", 1, 1, dateAddedSec = 9)),
        )
        val sorted = LibraryViewModel(library, folders, IdleScanner, playback, testSorting(sortStore), marks, coverFlow, NoBandLevels)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { sorted.uiState.collect {} }
        return sorted
    }

    @Test
    fun `songs, albums and artists start in their default sorts`() = runTest {
        val state = sortedLibrary().uiState.value

        assertThat(state.tracks.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(state.albums.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(state.artists.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(state.songsSort).isEqualTo(SortList.SONGS.default)
        assertThat(state.albumsSort).isEqualTo(SortList.ALBUMS.default)
        assertThat(state.artistsSort).isEqualTo(SortList.ARTISTS.default)
    }

    @Test
    fun `choosing a sort stores it for that list and re-sorts only that list`() = runTest {
        val library = sortedLibrary()
        val newestFirst = SortOrder(SortKey.DATE_ADDED, SortDirection.DESCENDING)

        library.setSort(SortList.SONGS, newestFirst)
        library.setSort(SortList.ARTISTS, SortOrder(SortKey.NAME, SortDirection.DESCENDING))

        val state = library.uiState.value
        assertThat(sortStore.stored.value[SortTarget(SortList.SONGS)]).isEqualTo(newestFirst)
        assertThat(state.songsSort).isEqualTo(newestFirst)
        assertThat(state.tracks.map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(state.artists.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(state.albums.map { it.id }).containsExactly(2L, 1L).inOrder()

        library.setSort(SortList.SONGS, SortOrder(SortKey.DATE_ADDED, SortDirection.ASCENDING))
        assertThat(library.uiState.value.tracks.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `a sort that cannot be saved is dropped without crashing`() = runTest {
        val library = sortedLibrary()
        sortStore.failure = IOException("disk full")

        library.setSort(SortList.SONGS, SortOrder(SortKey.DATE_ADDED, SortDirection.DESCENDING))

        assertThat(sortStore.stored.value).isEmpty()
        assertThat(library.uiState.value.songsSort).isEqualTo(SortList.SONGS.default)
    }

    @Test
    fun `a saved sort is applied when the library opens`() = runTest {
        sortStore.stored.value = mapOf(SortTarget(SortList.ALBUMS) to SortOrder(SortKey.DATE_ADDED, SortDirection.DESCENDING))

        assertThat(sortedLibrary().uiState.value.albums.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `pinned songs, albums and artists sit above the chosen sort, in pin order`() = runTest {
        marks.pins.value = listOf(
            PinContext.SONGS to ItemRef.song("content://doc/1"),
            PinContext.ALBUMS to ItemRef.album(1),
            PinContext.ARTISTS to ItemRef.artist(1),
        )
        val state = sortedLibrary().uiState.value

        assertThat(state.tracks.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(state.albums.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(state.artists.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(state.pinnedSongs).containsExactly("content://doc/1")
        assertThat(state.pinnedAlbums).containsExactly("1")
        assertThat(state.pinnedArtists).containsExactly("1")
    }

    @Test
    fun `an album page lists its pinned songs first`() = runTest {
        marks.pins.value = listOf(PinContext.album(5) to ItemRef.song("content://doc/2"))
        val listed = viewModel.albumTracks(5).first()
        assertThat(listed.items.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(listed.pinnedKeys).containsExactly("content://doc/2")
    }

    @Test
    fun `the Cover Flow artwork setting reaches the state while the Albums list keeps every album`() = runTest {
        val library = sortedLibrary()
        assertThat(library.uiState.value.hideAlbumsWithoutArtwork).isFalse()

        coverFlow.stored.value = true

        val state = library.uiState.value
        assertThat(state.hideAlbumsWithoutArtwork).isTrue()
        assertThat(state.albums.map { it.id }).containsExactly(2L, 1L).inOrder()
    }
}

/** Band levels that never have any: the view model only hands them to the Cover Flow. */
private object NoBandLevels : PlaybackBandLevels {
    override fun watch() = AutoCloseable {}

    override fun read(into: FloatArray) = false
}
