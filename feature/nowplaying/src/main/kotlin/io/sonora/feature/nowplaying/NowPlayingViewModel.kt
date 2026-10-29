package io.sonora.feature.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.lyrics.Lyrics
import io.sonora.core.domain.lyrics.LyricsLookup
import io.sonora.core.domain.lyrics.LyricsOrigin
import io.sonora.core.domain.lyrics.LyricsRepository
import io.sonora.core.domain.model.DecodedAudioFormat
import io.sonora.core.domain.model.NowPlayingRotation
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackFileInfo
import io.sonora.core.domain.playback.AbLoop
import io.sonora.core.domain.playback.AbLoopController
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.TrackFileInfoReader
import io.sonora.core.domain.port.VolumeController
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which optional buttons the Now Playing bottom row shows. */
data class NowPlayingButtons(val lyrics: Boolean, val fileInfo: Boolean)

/** What the File Info sheet needs from the player: the decoded format and the entry playing. */
internal data class PlayingFormat(val decoded: DecodedAudioFormat?, val itemId: Long?)

/** The player state reduced to [PlayingFormat], emitted only when that changes (not on every position tick). */
internal fun Flow<PlaybackState>.playingFormat(): Flow<PlayingFormat> =
    map { PlayingFormat(it.decodedFormat, it.currentItemId) }.distinctUntilChanged()

private val PlaybackState.currentItemId: Long? get() = queue.getOrNull(currentIndex)?.id

/**
 * The player state with its position zeroed, emitted only when something else changes. The player republishes the
 * state four times a second while it plays (and with every step of a tempo fader drag), and a screen composed from the
 * whole state recomposed with each; the position is read apart ([positions]) by what shows it.
 */
internal fun Flow<PlaybackState>.steady(): Flow<PlaybackState> = map(PlaybackState::steady).distinctUntilChanged()

internal fun PlaybackState.steady(): PlaybackState = if (positionMs == 0L) this else copy(positionMs = 0L)

/** The playback position, emitted when it changes. */
internal fun Flow<PlaybackState>.positions(): Flow<Long> = map { it.positionMs }.distinctUntilChanged()

/** The lyrics panel of the song playing. */
sealed interface LyricsPanel {
    /** Closed, disabled in Settings, or nothing playing: nothing is read. */
    data object Hidden : LyricsPanel

    /** Reading the song's own lyrics and the downloads kept on the device. */
    data object Loading : LyricsPanel

    /** [downloaded] lyrics came from an online search and can be removed. */
    data class Showing(val lyrics: Lyrics, val downloaded: Boolean) : LyricsPanel

    /** None on the device; the user may search online. */
    data object Missing : LyricsPanel

    data object Searching : LyricsPanel

    /** The online search found none; the user may search again. */
    data object NotFound : LyricsPanel

    /** The online search could not reach the service (offline, error); the user may retry. */
    data object Failed : LyricsPanel
}

private enum class LyricsCommand { SEARCH, REMOVE_DOWNLOADED }

/** The File Info sheet: closed, reading the file, its sections, or why it could not be read. */
sealed interface FileInfoSheet {
    data object Hidden : FileInfoSheet

    data object Loading : FileInfoSheet

    /**
     * The file's details and, while it is the entry playing, what the player decodes; worded by the sheet. [track] is
     * the track inspected, which Edit opens.
     */
    data class Loaded(val info: TrackFileInfo, val decoded: DecodedAudioFormat?, val track: Track) : FileInfoSheet

    data object Unreadable : FileInfoSheet
}

/**
 * Exposes the playback session, its queue and the system music volume to Now Playing and the mini player, plus the
 * optional lyrics and File Info buttons the user enabled in Settings, whether the song playing is a favourite, and the
 * player's A-B loop.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playback: PlaybackController,
    private val volumeController: VolumeController,
    preferences: PreferencesRepository,
    private val fileInfoReader: TrackFileInfoReader,
    private val lyricsRepository: LyricsRepository,
    private val marks: LibraryMarksRepository,
    private val loop: AbLoopController,
) : ViewModel() {
    val state: StateFlow<PlaybackState> = playback.state

    /**
     * [state] without its position (always 0 in it): what a screen composes from, so the player's four ticks a second do
     * not recompose it. What shows the position reads [position].
     */
    val steadyState: StateFlow<PlaybackState> =
        playback.state.steady().stateIn(viewModelScope, SharingStarted.Eagerly, playback.state.value.steady())

    /** The playback position (ms), as the player publishes it (four times a second while playing). */
    val position: StateFlow<Long> =
        playback.state.positions().stateIn(viewModelScope, SharingStarted.Eagerly, playback.state.value.positionMs)
    val volume: StateFlow<Float> = volumeController.volume

    // Declared before [buttons], whose eager collector writes it while the view model is being constructed.
    private val lyricsOpen = MutableStateFlow(false)

    /** Both hidden until the preferences are read, so a disabled button never flashes in. */
    val buttons: StateFlow<NowPlayingButtons> = preferences.preferences
        .map { NowPlayingButtons(lyrics = it.nowPlaying.showLyricsButton, fileInfo = it.nowPlaying.showFileInfoButton) }
        .onEach { if (!it.lyrics) lyricsOpen.value = false } // turning the button off closes the panel for good
        .stateIn(viewModelScope, SharingStarted.Eagerly, NowPlayingButtons(lyrics = false, fileInfo = false))

    /** What the shell shows when an open Now Playing turns sideways (Settings); the landscape layout until read. */
    val rotation: StateFlow<NowPlayingRotation> = preferences.preferences
        .map { it.nowPlaying.rotation }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NowPlayingRotation.NOW_PLAYING_LANDSCAPE)

    /**
     * Gravity mode (Settings): the window stays portrait and the two groups hang level with the phone's tilt; off until
     * read, so an open Now Playing never starts a sensor or holds portrait before the stored choice is known.
     */
    val gravity: StateFlow<Boolean> = preferences.preferences
        .map { it.nowPlaying.gravity }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The lyrics panel replaces the artwork; never while the lyrics button is disabled. */
    val showLyrics: StateFlow<Boolean> = combine(lyricsOpen, buttons) { open, enabled -> open && enabled.lyrics }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleLyrics() {
        if (buttons.value.lyrics) lyricsOpen.value = !lyricsOpen.value
    }

    private val lyricsCommands = MutableSharedFlow<LyricsCommand>(extraBufferCapacity = 1)

    /**
     * The lyrics of the song playing, read only while the panel is open (so never while the lyrics button is
     * disabled) and while Now Playing shows it. Online search runs only on [searchLyrics].
     */
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    val lyrics: StateFlow<LyricsPanel> = combine(showLyrics, playback.state.map { it.currentTrack }) { open, track ->
        track.takeIf { open }
    }
        .distinctUntilChanged()
        .flatMapLatest { track -> if (track == null) flowOf(LyricsPanel.Hidden) else lyricsOf(track) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsPanel.Hidden)

    private fun lyricsOf(track: Track): Flow<LyricsPanel> = channelFlow {
        // Commands are taken from the start, so one sent while the song's lyrics load is not lost.
        launch {
            lyricsCommands.collectLatest { command ->
                when (command) {
                    LyricsCommand.SEARCH -> {
                        send(LyricsPanel.Searching)
                        send(lyricsPanel { lyricsRepository.search(track) } ?: LyricsPanel.Failed)
                    }
                    LyricsCommand.REMOVE_DOWNLOADED -> {
                        // A failed delete keeps the lyrics on screen; letting it escape would end this flow.
                        val removed = try {
                            lyricsRepository.removeDownloaded(track)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        if (removed) send(LyricsPanel.Missing)
                    }
                }
            }
        }
        send(LyricsPanel.Loading)
        send(lyricsPanel { lyricsRepository.load(track) } ?: LyricsPanel.Missing)
    }

    /** The panel for [lookup]'s answer; `null` when it failed. */
    private suspend fun lyricsPanel(lookup: suspend () -> LyricsLookup): LyricsPanel? = try {
        when (val found = lookup()) {
            is LyricsLookup.Found -> LyricsPanel.Showing(found.lyrics, downloaded = found.origin == LyricsOrigin.DOWNLOADED)
            LyricsLookup.NotFound -> LyricsPanel.NotFound
            LyricsLookup.Missing -> LyricsPanel.Missing
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Whether this build can search lyrics online (the Google Play build cannot); without it the panel offers none. */
    val canSearchLyrics: Boolean get() = lyricsRepository.canSearchOnline

    /** Searches online for the song in the panel; only the user asks for this, and only where [canSearchLyrics]. */
    fun searchLyrics() {
        if (canSearchLyrics) lyricsCommands.tryEmit(LyricsCommand.SEARCH)
    }

    /** Forgets the downloaded lyrics of the song in the panel. */
    fun removeDownloadedLyrics() {
        lyricsCommands.tryEmit(LyricsCommand.REMOVE_DOWNLOADED)
    }

    /** The read in progress or done for the sheet, and the queue entry it was opened for. */
    private sealed interface FileInfoLoad {
        data object None : FileInfoLoad
        data object Reading : FileInfoLoad
        data class Read(val queueItemId: Long?, val track: Track, val info: TrackFileInfo) : FileInfoLoad
        data object Unreadable : FileInfoLoad
    }

    private val fileInfoLoad = MutableStateFlow<FileInfoLoad>(FileInfoLoad.None)
    private var fileInfoJob: Job? = null

    /**
     * The File Info sheet. Its Playback section shows the player's decoded format only while the inspected entry is
     * the one playing, so it never describes another track.
     */
    val fileInfo: StateFlow<FileInfoSheet> = combine(fileInfoLoad, playback.state.playingFormat()) { load, playing ->
        when (load) {
            FileInfoLoad.None -> FileInfoSheet.Hidden
            FileInfoLoad.Reading -> FileInfoSheet.Loading
            FileInfoLoad.Unreadable -> FileInfoSheet.Unreadable
            is FileInfoLoad.Read -> {
                val decoded = playing.decoded?.takeIf {
                    load.queueItemId != null && it.queueItemId == load.queueItemId && playing.itemId == load.queueItemId
                }
                FileInfoSheet.Loaded(load.info, decoded, load.track)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FileInfoSheet.Hidden)

    /** Opens the sheet for the current track and reads its file; nothing when nothing is loaded. */
    fun showFileInfo() {
        val playing = state.value
        val track = playing.currentTrack ?: return
        readFileInfo(playing.currentItemId, track)
    }

    /**
     * Reads the inspected file again as [track] (its details after a tag edit), for the same queue entry, so the
     * sheet shows what was saved.
     */
    fun reloadFileInfo(track: Track) {
        val itemId = (fileInfoLoad.value as? FileInfoLoad.Read)?.queueItemId
        readFileInfo(itemId, track)
    }

    private fun readFileInfo(itemId: Long?, track: Track) {
        fileInfoJob?.cancel()
        fileInfoLoad.value = FileInfoLoad.Reading
        fileInfoJob = viewModelScope.launch {
            fileInfoLoad.value = try {
                FileInfoLoad.Read(itemId, track, fileInfoReader.read(track))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FileInfoLoad.Unreadable
            }
        }
    }

    fun dismissFileInfo() {
        fileInfoJob?.cancel()
        fileInfoJob = null
        fileInfoLoad.value = FileInfoLoad.None
    }

    /** The song playing is one of the favourites (false while nothing plays): the star is filled. */
    val favourite: StateFlow<Boolean> = combine(
        playback.state.map { it.currentTrack?.uri }.distinctUntilChanged(),
        marks.favourites(MarkKind.SONG),
    ) { uri, favourites -> uri != null && uri in favourites }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Toggles run one at a time, so each one starts from what the one before stored. */
    private val favouriteToggles = Mutex()

    /**
     * Adds the song playing to the favourites, or removes it; a change that cannot be saved leaves the star as is.
     * Each tap toggles what is stored by the time it runs, not the star on screen: a second tap before the store
     * re-emitted takes the song out again instead of adding it twice.
     */
    fun toggleFavourite() {
        val track = state.value.currentTrack ?: return
        viewModelScope.launch {
            favouriteToggles.withLock {
                try {
                    val favourite = track.uri !in marks.favourites(MarkKind.SONG).first()
                    marks.setFavourite(ItemRef.song(track), favourite)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The star keeps showing what is stored.
                }
            }
        }
    }

    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.skipToNext()
    fun previous() = playback.skipToPrevious()
    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)
    fun toggleShuffle() = playback.setShuffle(!state.value.shuffleEnabled)
    fun cycleRepeat() = playback.cycleRepeatMode()
    fun clearUpcoming() = playback.clearUpcoming()

    // Up Next rows act on queue entries by id: a row's index can be stale by the time a gesture ends (the track
    // advanced, another edit landed), so it is resolved from the queue when the action runs.

    /** Plays the entry [itemId]; nothing if it left the queue. */
    fun skipToItem(itemId: Long) {
        indexOf(itemId)?.let(playback::skipToQueueItem)
    }

    /** Removes the entry [itemId]; nothing if it left the queue. */
    fun removeItem(itemId: Long) {
        indexOf(itemId)?.let(playback::removeQueueItem)
    }

    /** Moves the entry [itemId] [by] places (positive = later); nothing if it left the queue. */
    fun moveItem(itemId: Long, by: Int) {
        val from = indexOf(itemId) ?: return
        playback.moveQueueItem(from, from + by)
    }

    private fun indexOf(itemId: Long): Int? = state.value.queue.indexOfFirst { it.id == itemId }.takeIf { it >= 0 }
    fun setVolume(fraction: Float) = volumeController.setVolume(fraction)

    /** The player's A-B loop: its mode ("…" menu), its points and what the next tap does. */
    val abLoop: StateFlow<AbLoop> = loop.abLoop

    fun toggleAbLoop() = loop.toggleAbLoop()
    fun tapAbLoop() = loop.tapAbLoop()

    /** Nudges the scrubber's loop marker [index] (0 = A, 1 = B) to [positionMs]. */
    fun moveLoopPoint(index: Int, positionMs: Long) {
        if (index == 0) loop.moveLoopStart(positionMs) else loop.moveLoopEnd(positionMs)
    }
    fun refreshVolume() = volumeController.refresh()
}
