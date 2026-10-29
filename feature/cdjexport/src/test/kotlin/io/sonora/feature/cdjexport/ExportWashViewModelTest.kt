package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.LibraryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain, UnconfinedTestDispatcher
class ExportWashViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val library = FakeWashLibrary()
    private lateinit var viewModel: ExportWashViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = ExportWashViewModel(library, dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `nothing is shown until tracks are listed`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.artwork.collect {} }
        library.tracks.value = listOf(track("content://a", "content://art/a"))

        assertThat(viewModel.artwork.value).isEmpty()
    }

    @Test
    fun `listed tracks get the covers the library has for them`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.artwork.collect {} }
        library.tracks.value = listOf(
            track("content://a", "content://art/a"),
            track("content://b", null),
            track("content://c", "content://art/c"),
        )

        viewModel.show(listOf("content://a", "content://b"))

        assertThat(viewModel.artwork.value).containsExactly("content://a", "content://art/a")
    }

    @Test
    fun `a cover the scanner finds later appears without listing the tracks again`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.artwork.collect {} }
        library.tracks.value = listOf(track("content://a", null))
        viewModel.show(listOf("content://a"))
        assertThat(viewModel.artwork.value).isEmpty()

        library.tracks.value = listOf(track("content://a", "content://art/a"))

        assertThat(viewModel.artwork.value).containsExactly("content://a", "content://art/a")
    }

    @Test
    fun `clearing the list clears the covers`() = runTest(dispatcher) {
        backgroundScope.launch { viewModel.artwork.collect {} }
        library.tracks.value = listOf(track("content://a", "content://art/a"))
        viewModel.show(listOf("content://a"))
        assertThat(viewModel.artwork.value).hasSize(1)

        viewModel.show(emptyList())

        assertThat(viewModel.artwork.value).isEmpty()
    }

    private fun track(uri: String, artwork: String?) = Track(
        id = uri.hashCode().toLong(),
        uri = uri,
        title = "Title",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        durationMs = 1_000L,
        trackNumber = null,
        discNumber = null,
        year = null,
        albumId = 1L,
        artworkUri = artwork,
        mimeType = "audio/aiff",
        sizeBytes = 1_000L,
    )
}

/** A library whose tracks the test sets; it re-emits when they change, as Room does. */
private class FakeWashLibrary : LibraryRepository {
    val tracks = MutableStateFlow<List<Track>>(emptyList())

    override fun tracks(sort: TrackSort): Flow<List<Track>> = tracks
    override fun albums(): Flow<List<Album>> = emptyFlow()
    override fun artists(): Flow<List<Artist>> = emptyFlow()
    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = emptyFlow()
    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = emptyFlow()
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> = emptyFlow()
    override fun search(query: String): Flow<List<Track>> = flowOf(emptyList())
}
