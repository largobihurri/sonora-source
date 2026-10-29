package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.PendingSource
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import java.io.IOException
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
class ConvertViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val tracks = listOf(libraryTrack(1, album = 10), libraryTrack(2, album = 10), libraryTrack(3, album = 20))
    private val library = FakeTrackLibrary(tracks, listOf(album(10, artist = 1), album(20, artist = 1)))
    private val pending = PendingConversionSources()
    private lateinit var viewModel: ConvertViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = ConvertViewModel(library, FakeFolderBrowser(listOf(tracks[2])), pending)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectEvents(): MutableList<ConvertEvent> {
        val events = mutableListOf<ConvertEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.toList(events) }
        return events
    }

    private fun song(track: Track) = PendingSource(PickedSource.Track(track.uri), track.title, detail = track.artist)

    @Test
    fun `an album's songs wait for the converter as library songs, and the converter opens`() = runTest(dispatcher) {
        val events = collectEvents()

        viewModel.convert(PlaylistSource.Album(10))

        assertThat(pending.sources.value).containsExactly(song(tracks[0]), song(tracks[1])).inOrder()
        assertThat(events).containsExactly(ConvertEvent.Sent(2))
    }

    @Test
    fun `a library song waits with the codec the library read`() = runTest(dispatcher) {
        viewModel.convert(PlaylistSource.Tracks(listOf(tracks[1].copy(codec = "ALAC"))))

        assertThat(pending.sources.value.single().codec).isEqualTo("ALAC")
    }

    @Test
    fun `songs already waiting are listed once`() = runTest(dispatcher) {
        viewModel.convert(PlaylistSource.Tracks(listOf(tracks[1])))
        viewModel.convert(PlaylistSource.Album(10))
        viewModel.convert(PlaylistSource.Folder(FolderRef("content://tree/music", "Music")))

        assertThat(pending.sources.value).containsExactly(song(tracks[1]), song(tracks[0]), song(tracks[2])).inOrder()
    }

    @Test
    fun `a source without songs sends nothing and says so`() = runTest(dispatcher) {
        val events = collectEvents()

        viewModel.convert(PlaylistSource.Album(99))

        assertThat(pending.sources.value).isEmpty()
        assertThat(events).containsExactly(ConvertEvent.NothingToConvert)
    }

    @Test
    fun `songs that cannot be read are reported`() = runTest(dispatcher) {
        val unreadable = object : FolderBrowser by FakeFolderBrowser(tracks) {
            override suspend fun tracksUnder(folder: FolderRef): List<Track> = throw IOException("index gone")
        }
        viewModel = ConvertViewModel(library, unreadable, pending)
        val events = collectEvents()

        viewModel.convert(PlaylistSource.Folder(FolderRef("content://tree/music", "Music")))

        assertThat(pending.sources.value).isEmpty()
        assertThat(events).containsExactly(ConvertEvent.Failed)
    }
}
