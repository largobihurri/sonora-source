package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.lyrics.LocalLyrics
import io.sonora.core.domain.lyrics.LyricLine
import io.sonora.core.domain.lyrics.Lyrics
import io.sonora.core.domain.lyrics.LyricsOrigin
import io.sonora.core.domain.lyrics.LyricsRepository
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AudioStreamInfo
import io.sonora.core.domain.model.DecodedAudioFormat
import io.sonora.core.domain.model.FileDetails
import io.sonora.core.domain.model.FileLocation
import io.sonora.core.domain.model.NowPlayingPreferences
import io.sonora.core.domain.model.NowPlayingRotation
import io.sonora.core.domain.model.PcmEncoding
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackFileInfo
import io.sonora.core.domain.playback.AbLoop
import io.sonora.core.domain.playback.AbLoopController
import io.sonora.core.domain.port.CachedLyrics
import io.sonora.core.domain.port.LocalLyricsSource
import io.sonora.core.domain.port.LyricsCache
import io.sonora.core.domain.port.LyricsQuery
import io.sonora.core.domain.port.OnlineLyrics
import io.sonora.core.domain.port.OnlineLyricsSource
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.TrackFileInfoReader
import io.sonora.core.domain.port.VolumeController
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Up Next row actions name queue entries (the index is resolved from the queue at the moment the action runs); the
 * optional buttons follow the user's preferences; File Info is read on demand for the current track.
 */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class NowPlayingViewModelTest {

    private class RecordingPlayback : PlaybackController {
        val calls = mutableListOf<String>()
        override val state = MutableStateFlow(PlaybackState())
        override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) = Unit
        override fun playNow(tracks: List<Track>) = Unit
        override fun playNext(tracks: List<Track>) = Unit
        override fun addToQueue(tracks: List<Track>) = Unit
        override fun moveQueueItem(from: Int, to: Int) { calls += "move $from $to" }
        override fun removeQueueItem(index: Int) { calls += "remove $index" }
        override fun clearUpcoming() = Unit
        override fun skipToQueueItem(index: Int) { calls += "skip $index" }
        override fun play() = Unit
        override fun pause() = Unit
        override fun togglePlayPause() = Unit
        override fun skipToNext() = Unit
        override fun skipToPrevious() = Unit
        override fun seekTo(positionMs: Long) { calls += "seek $positionMs" }
        override fun setShuffle(enabled: Boolean) = Unit
        override fun setRepeatMode(mode: RepeatMode) = Unit
        override fun cycleRepeatMode() = Unit
    }

    private object FixedVolume : VolumeController {
        override val volume: StateFlow<Float> = MutableStateFlow(0.5f)
        override fun setVolume(fraction: Float) = Unit
        override fun refresh() = Unit
    }

    private class FakePreferences : PreferencesRepository {
        val stored = MutableStateFlow(AppPreferences())
        override val preferences: Flow<AppPreferences> = stored
        override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
            stored.value = transform(stored.value)
        }
    }

    /** Answers each read with the next [answers] entry once it is completed. */
    private class FakeReader : TrackFileInfoReader {
        val requested = mutableListOf<Long>()
        var answer = CompletableDeferred<TrackFileInfo>()
        override suspend fun read(track: Track): TrackFileInfo {
            requested += track.id
            return answer.await()
        }
    }

    /** Lyrics on the device by track URI; every read is recorded. Lyric texts are invented for the tests. */
    private class FakeLocalLyrics : LocalLyricsSource {
        val reads = mutableListOf<String>()
        val songs = mutableMapOf<String, LocalLyrics>()

        /** When set, reads wait for it. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read(track: Track): LocalLyrics? {
            reads += track.uri
            gate?.await()
            return songs[track.uri]
        }
    }

    private class FakeCache : LyricsCache {
        val entries = mutableMapOf<String, CachedLyrics>()
        var removeFails = false
        override suspend fun get(trackUri: String) = entries[trackUri]
        override suspend fun put(trackUri: String, entry: CachedLyrics) {
            entries[trackUri] = entry
        }
        override suspend fun remove(trackUri: String) {
            if (removeFails) throw IOException("disk full")
            entries.remove(trackUri)
        }
    }

    /** Answers each search with [answer] once it is completed. */
    private class FakeOnline : OnlineLyricsSource {
        val queries = mutableListOf<LyricsQuery>()
        var answer = CompletableDeferred<OnlineLyrics?>()
        override suspend fun find(query: LyricsQuery): OnlineLyrics? {
            queries += query
            return answer.await()
        }
    }

    /**
     * Favourites only; [failing] makes every change throw, like a database error, and [slow] holds every change until
     * it completes, like a busy database.
     */
    private class FakeMarks : LibraryMarksRepository {
        val favourites = MutableStateFlow<Set<ItemRef>>(emptySet())
        var failing = false
        var slow: CompletableDeferred<Unit>? = null
        override fun favourites(kind: MarkKind): Flow<List<String>> =
            favourites.map { all -> all.filter { it.kind == kind }.map { it.key } }
        override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) {
            slow?.await()
            if (failing) throw IOException("disk full")
            favourites.value = if (favourite) favourites.value + ref else favourites.value - ref
        }
        override fun pins(context: PinContext): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) = Unit
        override fun hidden(): Flow<List<HiddenItem>> = MutableStateFlow(emptyList())
        override suspend fun hide(item: HiddenItem) = Unit
        override suspend fun restore(ref: ItemRef) = Unit
        override suspend fun forgetSongs(uris: Collection<String>) = Unit
    }

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", title = "T$id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    private class RecordingLoop : AbLoopController {
        val calls = mutableListOf<String>()
        override val abLoop = MutableStateFlow(AbLoop())
        override fun toggleAbLoop() { calls += "toggle" }
        override fun tapAbLoop() { calls += "tap" }
        override fun moveLoopStart(positionMs: Long) { calls += "start $positionMs" }
        override fun moveLoopEnd(positionMs: Long) { calls += "end $positionMs" }
    }

    private val playback = RecordingPlayback()
    private val loop = RecordingLoop()
    private val preferences = FakePreferences()
    private val reader = FakeReader()
    private val localLyrics = FakeLocalLyrics()
    private val lyricsCache = FakeCache()
    private val online = FakeOnline()
    private val marks = FakeMarks()
    private lateinit var viewModel: NowPlayingViewModel
    private var lyricsCollector: Job? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = NowPlayingViewModel(
            playback, FixedVolume, preferences, reader, LyricsRepository(localLyrics, lyricsCache, online), marks, loop,
        )
    }

    @After
    fun tearDown() {
        lyricsCollector?.cancel()
        Dispatchers.resetMain()
    }

    /** Entries 10, 11, 12, 13, 14 (item ids) with [current] playing. */
    private fun queue(current: Int) {
        playback.state.value = PlaybackState(
            queue = (10L..14L).map { QueueItem(it, track(it)) },
            currentIndex = current,
        )
    }

    @Test
    fun `row actions resolve the entry's index when they run, not when the row was drawn`() {
        queue(current = 0)
        // The row for entry 13 was drawn at queue index 3; then the queue moved on by one track.
        queue(current = 1)
        playback.state.value = playback.state.value.copy(
            queue = playback.state.value.queue.filter { it.id != 10L },
            currentIndex = 0,
        )

        viewModel.skipToItem(13)
        viewModel.removeItem(12)

        assertThat(playback.calls).containsExactly("skip 2", "remove 1").inOrder()
    }

    @Test
    fun `the A-B loop commands reach the player's loop, markers by index`() {
        viewModel.toggleAbLoop()
        viewModel.tapAbLoop()
        viewModel.moveLoopPoint(0, 10_000)
        viewModel.moveLoopPoint(1, 14_000)

        assertThat(loop.calls).containsExactly("toggle", "tap", "start 10000", "end 14000").inOrder()
    }

    @Test
    fun `the A-B loop state is the player's`() {
        loop.abLoop.value = AbLoop(enabled = true, startMs = 1_000, itemId = 3)

        assertThat(viewModel.abLoop.value).isEqualTo(AbLoop(enabled = true, startMs = 1_000, itemId = 3))
    }

    @Test
    fun `actions on entries that left the queue do nothing`() {
        queue(current = 0)

        viewModel.skipToItem(99)
        viewModel.removeItem(99)
        viewModel.moveItem(99, by = 1)

        assertThat(playback.calls).isEmpty()
    }

    @Test
    fun `a drop moves the entry by the distance it was dragged`() {
        queue(current = 1)

        viewModel.moveItem(12, by = 2)
        viewModel.moveItem(14, by = -1)

        assertThat(playback.calls).containsExactly("move 2 4", "move 4 3").inOrder()
    }

    private fun nowPlaying(lyrics: Boolean, fileInfo: Boolean) {
        preferences.stored.value = AppPreferences(NowPlayingPreferences(showLyricsButton = lyrics, showFileInfoButton = fileInfo))
    }

    @Test
    fun `the optional buttons follow the preferences, lyrics hidden and file info shown by default`() {
        assertThat(viewModel.buttons.value).isEqualTo(NowPlayingButtons(lyrics = false, fileInfo = true))

        nowPlaying(lyrics = true, fileInfo = false)

        assertThat(viewModel.buttons.value).isEqualTo(NowPlayingButtons(lyrics = true, fileInfo = false))
    }

    @Test
    fun `the rotation choice follows the preferences, the landscape Now Playing by default`() {
        assertThat(viewModel.rotation.value).isEqualTo(NowPlayingRotation.NOW_PLAYING_LANDSCAPE)

        preferences.stored.value = AppPreferences(NowPlayingPreferences(rotation = NowPlayingRotation.ALBUM_VISUAL_COLLECTION))

        assertThat(viewModel.rotation.value).isEqualTo(NowPlayingRotation.ALBUM_VISUAL_COLLECTION)
    }

    @Test
    fun `gravity mode follows the preferences, off by default`() {
        assertThat(viewModel.gravity.value).isFalse()

        preferences.stored.value = AppPreferences(NowPlayingPreferences(gravity = true))

        assertThat(viewModel.gravity.value).isTrue()
    }

    @Test
    fun `lyrics open only while the lyrics button is enabled and close when it is turned off`() {
        viewModel.toggleLyrics()
        assertThat(viewModel.showLyrics.value).isFalse() // button hidden: nothing opens

        nowPlaying(lyrics = true, fileInfo = true)
        viewModel.toggleLyrics()
        assertThat(viewModel.showLyrics.value).isTrue()

        nowPlaying(lyrics = false, fileInfo = true)
        assertThat(viewModel.showLyrics.value).isFalse()
        nowPlaying(lyrics = true, fileInfo = true)
        assertThat(viewModel.showLyrics.value).isFalse() // turning it back on does not reopen the panel
    }

    private fun info(name: String) = TrackFileInfo(
        location = FileLocation("Music", "", name, "content://doc/$name"),
        file = FileDetails("FLAC", 1_024, null),
        audio = AudioStreamInfo(null, null, 44_100, null, 2, null, variableBitrate = false, durationMs = null),
        tags = null,
    )

    @Test
    fun `file info loads for the current track and shows its sections`() {
        playback.state.value = PlaybackState(currentTrack = track(10), queue = listOf(QueueItem(5, track(10))), currentIndex = 0)

        viewModel.showFileInfo()

        assertThat(reader.requested).containsExactly(10L)
        assertThat(viewModel.fileInfo.value).isEqualTo(FileInfoSheet.Loading)
        reader.answer.complete(info("a.flac"))
        val loaded = viewModel.fileInfo.value as FileInfoSheet.Loaded
        assertThat(loaded.info.location.fileName).isEqualTo("a.flac")
        viewModel.dismissFileInfo()
        assertThat(viewModel.fileInfo.value).isEqualTo(FileInfoSheet.Hidden)
    }

    @Test
    fun `after an edit, file info reads the retagged track again for the same entry`() {
        playback.state.value = PlaybackState(currentTrack = track(10), queue = listOf(QueueItem(5, track(10))), currentIndex = 0)
        viewModel.showFileInfo()
        reader.answer.complete(info("a.flac"))
        assertThat((viewModel.fileInfo.value as FileInfoSheet.Loaded).track).isEqualTo(track(10))
        // Meanwhile the next track started; the sheet still describes the file it was opened for.
        playback.state.value = PlaybackState(currentTrack = track(11), queue = listOf(QueueItem(6, track(11))), currentIndex = 0)
        val retagged = track(10).copy(title = "New")
        reader.answer = CompletableDeferred()

        viewModel.reloadFileInfo(retagged)
        reader.answer.complete(info("a.flac"))

        assertThat(reader.requested).containsExactly(10L, 10L).inOrder()
        assertThat((viewModel.fileInfo.value as FileInfoSheet.Loaded).track).isEqualTo(retagged)
    }

    @Test
    fun `a file that cannot be read shows an error`() {
        playback.state.value = PlaybackState(currentTrack = track(10))

        viewModel.showFileInfo()
        reader.answer.completeExceptionally(FileNotFoundException("gone"))

        assertThat(viewModel.fileInfo.value).isEqualTo(FileInfoSheet.Unreadable)
    }

    @Test
    fun `nothing playing opens nothing`() {
        viewModel.showFileInfo()

        assertThat(reader.requested).isEmpty()
        assertThat(viewModel.fileInfo.value).isEqualTo(FileInfoSheet.Hidden)
    }

    @Test
    fun `the playback section appears only while the inspected entry is the one playing`() {
        val decoded = DecodedAudioFormat(5, "audio/raw", PcmEncoding.PCM_16, 44_100, 2, PcmEncoding.PCM_16, 44_100)
        playback.state.value = PlaybackState(currentTrack = track(10), queue = listOf(QueueItem(5, track(10))), currentIndex = 0)
        viewModel.showFileInfo()
        reader.answer.complete(info("a.flac"))
        fun titles() = (viewModel.fileInfo.value as FileInfoSheet.Loaded)
            .let { FileInfoText.sections(it.info, it.decoded, XmlStrings.of()) }
            .map { it.title }
        assertThat(titles()).doesNotContain("Playback")

        playback.state.value = playback.state.value.copy(decodedFormat = decoded)
        assertThat(titles()).contains("Playback")

        playback.state.value = PlaybackState(
            currentTrack = track(11), queue = listOf(QueueItem(6, track(11))), currentIndex = 0,
            decodedFormat = decoded.copy(queueItemId = 6),
        )
        assertThat(titles()).doesNotContain("Playback")
    }

    @Test
    fun `File Info listens only to the decoded format and the playing entry, not to position ticks`() = runTest {
        val decoded = DecodedAudioFormat(5, "audio/raw", PcmEncoding.PCM_16, 44_100, 2, PcmEncoding.PCM_16, 44_100)
        val state = MutableStateFlow(PlaybackState(currentTrack = track(10), queue = listOf(QueueItem(5, track(10))), currentIndex = 0))
        val seen = mutableListOf<PlayingFormat>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.playingFormat().toList(seen) }

        for (position in 1L..5L) state.value = state.value.copy(positionMs = position * 1_000, isPlaying = position % 2 == 0L)
        assertThat(seen).containsExactly(PlayingFormat(decoded = null, itemId = 5))

        state.value = state.value.copy(decodedFormat = decoded)
        state.value = state.value.copy(positionMs = 9_000)
        state.value = state.value.copy(queue = listOf(QueueItem(5, track(10)), QueueItem(6, track(11))), currentIndex = 1)

        assertThat(seen).containsExactly(
            PlayingFormat(decoded = null, itemId = 5),
            PlayingFormat(decoded = decoded, itemId = 5),
            PlayingFormat(decoded = decoded, itemId = 6),
        ).inOrder()
    }

    @Test
    fun `the steady state does not tick with the position, and every other change reaches it`() = runTest {
        val state = MutableStateFlow(PlaybackState(currentTrack = track(10), queue = listOf(QueueItem(5, track(10))), currentIndex = 0))
        val seen = mutableListOf<PlaybackState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.steady().toList(seen) }

        state.value = state.value.copy(isPlaying = true)
        // A track playing ticks four times a second, and a tempo fader drag republishes the state with every step.
        for (position in 1L..20L) state.value = state.value.copy(positionMs = position * 250)
        assertThat(seen).hasSize(2) // the first state, then the one that started playing
        assertThat(seen.map { it.positionMs }.distinct()).containsExactly(0L)

        state.value = state.value.copy(positionMs = 9_000, durationMs = 180_000)
        assertThat(seen).hasSize(3)
        assertThat(seen.last().durationMs).isEqualTo(180_000)
        assertThat(seen.last().isPlaying).isTrue()
    }

    @Test
    fun `the position flow carries the position alone`() = runTest {
        val state = MutableStateFlow(PlaybackState(positionMs = 100, isPlaying = false))
        val seen = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.positions().toList(seen) }

        state.value = state.value.copy(isPlaying = true) // not a position change
        state.value = state.value.copy(positionMs = 350)
        state.value = state.value.copy(positionMs = 350, durationMs = 1_000)
        state.value = state.value.copy(positionMs = 600)

        assertThat(seen).containsExactly(100L, 350L, 600L).inOrder()
    }

    @Test
    fun `the view model hands the screens the steady state and the position apart`() {
        playback.state.value = PlaybackState(currentTrack = track(10), isPlaying = true, positionMs = 12_000)

        assertThat(viewModel.steadyState.value.currentTrack).isEqualTo(track(10))
        assertThat(viewModel.steadyState.value.positionMs).isEqualTo(0L)
        assertThat(viewModel.position.value).isEqualTo(12_000L)

        val before = viewModel.steadyState.value
        playback.state.value = playback.state.value.copy(positionMs = 12_250)
        assertThat(viewModel.steadyState.value).isSameInstanceAs(before)
        assertThat(viewModel.position.value).isEqualTo(12_250L)
    }

    // Lyrics

    /** The panel as Now Playing sees it while on screen. */
    private fun watchLyrics(): MutableList<LyricsPanel> {
        val seen = mutableListOf<LyricsPanel>()
        lyricsCollector = CoroutineScope(UnconfinedTestDispatcher()).launch { viewModel.lyrics.collect { seen += it } }
        return seen
    }

    private fun playing(id: Long) {
        playback.state.value = PlaybackState(currentTrack = track(id), queue = listOf(QueueItem(id, track(id))), currentIndex = 0)
    }

    private val synced = Lyrics.Synced(listOf(LyricLine(1_000, "First test line"), LyricLine(2_000, "Second test line")))

    @Test
    fun `with the lyrics button disabled nothing lyrics-related loads`() {
        val seen = watchLyrics()
        playing(10)

        viewModel.toggleLyrics()
        viewModel.searchLyrics()

        assertThat(localLyrics.reads).isEmpty()
        assertThat(online.queries).isEmpty()
        assertThat(seen).containsExactly(LyricsPanel.Hidden)
    }

    @Test
    fun `lyrics load only while the panel is open, for the song playing`() {
        localLyrics.songs[track(10).uri] = LocalLyrics(synced, LyricsOrigin.SIDECAR)
        val seen = watchLyrics()
        nowPlaying(lyrics = true, fileInfo = true)
        playing(10)
        assertThat(localLyrics.reads).isEmpty() // closed panel: nothing read
        localLyrics.gate = CompletableDeferred()

        viewModel.toggleLyrics()
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Loading)
        localLyrics.gate!!.complete(Unit)

        assertThat(seen).containsExactly(LyricsPanel.Hidden, LyricsPanel.Loading, LyricsPanel.Showing(synced, downloaded = false)).inOrder()
        playing(11)
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Missing)
        viewModel.toggleLyrics()
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Hidden)
        assertThat(localLyrics.reads).containsExactly(track(10).uri, track(11).uri).inOrder()
        assertThat(online.queries).isEmpty() // never online by itself
    }

    @Test
    fun `a build without online lyrics offers no search and never searches`() {
        viewModel = NowPlayingViewModel(
            playback, FixedVolume, preferences, reader, LyricsRepository(localLyrics, lyricsCache, online = null), marks, loop,
        )
        watchLyrics()
        nowPlaying(lyrics = true, fileInfo = true)
        playing(10)
        viewModel.toggleLyrics()

        assertThat(viewModel.canSearchLyrics).isFalse()
        viewModel.searchLyrics()
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Missing)
        assertThat(lyricsCache.entries).isEmpty()
    }

    @Test
    fun `a search finds lyrics once and they are served from the device afterwards`() {
        val seen = watchLyrics()
        nowPlaying(lyrics = true, fileInfo = true)
        playing(10)
        viewModel.toggleLyrics()
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Missing)

        viewModel.searchLyrics()
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Searching)
        online.answer.complete(OnlineLyrics("[00:01.00]First test line\n[00:02.00]Second test line", null, false))

        assertThat(seen.last()).isEqualTo(LyricsPanel.Showing(synced, downloaded = true))
        playing(11)
        playing(10)
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Showing(synced, downloaded = true))
        assertThat(online.queries).hasSize(1)
    }

    @Test
    fun `a song without lyrics online shows not found and can be searched again`() {
        watchLyrics()
        nowPlaying(lyrics = true, fileInfo = true)
        playing(10)
        viewModel.toggleLyrics()

        viewModel.searchLyrics()
        online.answer.complete(null)
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.NotFound)

        online.answer = CompletableDeferred()
        viewModel.searchLyrics()
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Searching)
        online.answer.complete(OnlineLyrics(null, "First test line", false))
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Showing(Lyrics.Plain("First test line"), downloaded = true))
        assertThat(online.queries).hasSize(2)
    }

    @Test
    fun `an unreachable service shows an error to retry`() {
        watchLyrics()
        nowPlaying(lyrics = true, fileInfo = true)
        playing(10)
        viewModel.toggleLyrics()

        viewModel.searchLyrics()
        online.answer.completeExceptionally(IOException("offline"))

        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Failed)
        assertThat(lyricsCache.entries).isEmpty()
    }

    @Test
    fun `removing downloaded lyrics brings the search back`() {
        lyricsCache.entries[track(10).uri] = CachedLyrics.Found(OnlineLyrics(null, "First test line", false))
        watchLyrics()
        nowPlaying(lyrics = true, fileInfo = true)
        playing(10)
        viewModel.toggleLyrics()
        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Showing(Lyrics.Plain("First test line"), downloaded = true))

        viewModel.removeDownloadedLyrics()

        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Missing)
        assertThat(lyricsCache.entries).isEmpty()
    }

    @Test
    fun `a failed removal keeps the downloaded lyrics on screen`() {
        lyricsCache.entries[track(10).uri] = CachedLyrics.Found(OnlineLyrics(null, "First test line", false))
        lyricsCache.removeFails = true
        watchLyrics()
        nowPlaying(lyrics = true, fileInfo = true)
        playing(10)
        viewModel.toggleLyrics()

        viewModel.removeDownloadedLyrics()

        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Showing(Lyrics.Plain("First test line"), downloaded = true))
        assertThat(lyricsCache.entries).isNotEmpty()

        // The panel must still answer: a removal that throws must not end the lyrics flow.
        lyricsCache.removeFails = false
        viewModel.removeDownloadedLyrics()

        assertThat(viewModel.lyrics.value).isEqualTo(LyricsPanel.Missing)
    }

    @Test
    fun `tapping a line seeks to it`() {
        viewModel.seekTo(2_000)

        assertThat(playback.calls).containsExactly("seek 2000")
    }

    // Favourite star

    @Test
    fun `the star shows whether the song playing is a favourite`() {
        assertThat(viewModel.favourite.value).isFalse()
        marks.favourites.value = setOf(ItemRef.song(track(1)))
        playing(1)
        assertThat(viewModel.favourite.value).isTrue()
        playing(2)
        assertThat(viewModel.favourite.value).isFalse()
        marks.favourites.value = setOf(ItemRef.song(track(1)), ItemRef.song(track(2)))
        assertThat(viewModel.favourite.value).isTrue()
    }

    @Test
    fun `tapping the star adds the song playing to the favourites and tapping again removes it`() {
        playing(1)
        viewModel.toggleFavourite()
        assertThat(marks.favourites.value).containsExactly(ItemRef.song(track(1)))
        assertThat(viewModel.favourite.value).isTrue()
        viewModel.toggleFavourite()
        assertThat(marks.favourites.value).isEmpty()
        assertThat(viewModel.favourite.value).isFalse()
    }

    @Test
    fun `with nothing playing the star changes nothing`() {
        viewModel.toggleFavourite()
        assertThat(marks.favourites.value).isEmpty()
    }

    @Test
    fun `a favourite that cannot be saved leaves the star as it was`() {
        playing(1)
        marks.failing = true
        viewModel.toggleFavourite()
        assertThat(marks.favourites.value).isEmpty()
        assertThat(viewModel.favourite.value).isFalse()
    }

    @Test
    fun `a double tap before the first change is stored adds the song and takes it out again`() {
        playing(1)
        val slow = CompletableDeferred<Unit>()
        marks.slow = slow

        viewModel.toggleFavourite()
        viewModel.toggleFavourite()
        slow.complete(Unit)

        assertThat(marks.favourites.value).isEmpty()
        assertThat(viewModel.favourite.value).isFalse()
    }
}
