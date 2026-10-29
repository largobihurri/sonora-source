package io.sonora.core.api

import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.playback.CrossfadeRepository
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.PlaybackTempoRepository
import io.sonora.core.domain.playback.SleepTimer
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

fun track(
    id: Long,
    title: String = "Title $id",
    artist: String = "Artist",
    album: String = "Album",
    albumId: Long = 1,
    artistId: Long = 1,
    trackNumber: Int? = null,
    discNumber: Int? = null,
) = Track(
    id = id, uri = "content://media/$id", title = title, artist = artist, album = album, albumArtist = null,
    durationMs = 180_000, trackNumber = trackNumber, discNumber = discNumber, year = 2020, albumId = albumId,
    artistId = artistId, artworkUri = "content://art/$albumId", mimeType = "audio/flac", sizeBytes = 1,
    folderPath = "/secret/folder",
)

/** Records every call as text, so a test reads what the dispatcher asked of the player. */
class FakePlayback(initial: PlaybackState = PlaybackState()) : PlaybackController {
    val calls = mutableListOf<String>()
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<PlaybackState> = mutable
    var failWith: RuntimeException? = null

    private fun record(call: String) {
        failWith?.let { throw it }
        calls += call
    }

    private fun ids(tracks: List<Track>) = tracks.joinToString(",") { it.id.toString() }

    override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) =
        record("playQueue[${ids(queue)}] start=$startIndex shuffle=$shuffle")
    override fun playNow(tracks: List<Track>) = record("playNow[${ids(tracks)}]")
    override fun playNext(tracks: List<Track>) = record("playNext[${ids(tracks)}]")
    override fun addToQueue(tracks: List<Track>) = record("addToQueue[${ids(tracks)}]")
    override fun moveQueueItem(from: Int, to: Int) = record("move $from->$to")
    override fun removeQueueItem(index: Int) = record("remove $index")
    override fun clearUpcoming() = record("clearUpcoming")
    override fun skipToQueueItem(index: Int) = record("skipTo $index")
    override fun play() = record("play")
    override fun pause() = record("pause")
    override fun togglePlayPause() = record("toggle")
    override fun skipToNext() = record("next")
    override fun skipToPrevious() = record("previous")
    override fun seekTo(positionMs: Long) = record("seek $positionMs")
    override fun setShuffle(enabled: Boolean) = record("shuffle $enabled")
    override fun setRepeatMode(mode: RepeatMode) = record("repeat $mode")
    override fun cycleRepeatMode() = record("cycleRepeat")
}

/** A queue of tracks [ids] with the item at [current] playing. */
fun playbackOf(vararg ids: Long, current: Int = 0, isPlaying: Boolean = true): PlaybackState {
    val queue = ids.mapIndexed { index, id -> QueueItem(id = 100L + index, track = track(id)) }
    return PlaybackState(
        currentTrack = queue.getOrNull(current)?.track, queue = queue, isPlaying = isPlaying, positionMs = 12_000,
        durationMs = 180_000, currentIndex = if (queue.isEmpty()) -1 else current, isConnected = true,
    )
}

class FakeSleepTimer(initial: SleepTimerState? = null) : SleepTimer {
    val calls = mutableListOf<String>()
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SleepTimerState?> = mutable
    override fun start(option: SleepTimerOption) {
        calls += "start $option"
        mutable.value = SleepTimerState(option, remainingMs = (option as? SleepTimerOption.Minutes)?.minutes?.times(60_000L))
    }
    override fun extend(minutes: Int) { calls += "extend $minutes" }
    override fun cancel() { calls += "cancel"; mutable.value = null }
}

class FakeTempo(initial: PlaybackTempo = PlaybackTempo.DEFAULT) : PlaybackTempoRepository {
    private val mutable = MutableStateFlow(initial)
    override val tempo: Flow<PlaybackTempo> = mutable
    override fun preview(tempo: PlaybackTempo?) = Unit
    override suspend fun update(transform: (PlaybackTempo) -> PlaybackTempo) { mutable.value = transform(mutable.value) }
    val current get() = mutable.value
}

class FakeCrossfade(initial: Int = 0) : CrossfadeRepository {
    private val mutable = MutableStateFlow(initial)
    override val seconds: Flow<Int> = mutable
    override suspend fun setSeconds(seconds: Int) { mutable.value = seconds }
    val current get() = mutable.value
}

class FakeLibrary(
    var tracks: List<Track> = emptyList(),
    var albums: List<Album> = emptyList(),
    var artists: List<Artist> = emptyList(),
) : LibraryRepository {
    override fun tracks(sort: TrackSort): Flow<List<Track>> = flowOf(LibraryQuery.sort(tracks, sort))
    override fun albums(): Flow<List<Album>> = flowOf(albums)
    override fun artists(): Flow<List<Artist>> = flowOf(artists)
    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = flowOf(albums.take(limit))
    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = flowOf(tracks.filter { it.albumId == albumId })
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> = flowOf(albums.filter { it.artistId == artistId })
    override fun search(query: String): Flow<List<Track>> = flowOf(LibraryQuery.search(tracks, query))
}

class FakePlaylists(var playlists: List<PlaylistWithEntries> = emptyList()) : PlaylistRepository {
    override fun playlists(): Flow<List<PlaylistWithEntries>> = flowOf(playlists)
    override fun playlist(id: Long): Flow<PlaylistWithEntries?> = flowOf(playlists.firstOrNull { it.playlist.id == id })
    override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>): Long = error("read only")
    override suspend fun updateDetails(id: Long, name: String, description: String) = error("read only")
    override suspend fun delete(id: Long) = error("read only")
    override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = error("read only")
}

fun playlistOf(id: Long, name: String, vararg tracks: Track) = PlaylistWithEntries(
    Playlist(id = id, name = name, description = "About $name"),
    tracks.map { PlaylistEntry.of(it) },
)

class FakeDjMode(initial: Boolean = false) : DjModeSettings {
    private val mutable = MutableStateFlow(initial)
    override val enabled: StateFlow<Boolean> = mutable
    override fun setEnabled(enabled: Boolean) { mutable.value = enabled }
    override val performance: StateFlow<PerformanceMode> = MutableStateFlow(PerformanceMode.DEFAULT)
    override fun setPerformance(performance: PerformanceMode) = Unit
}

/** The fakes behind an [ApiPorts], kept so a test can read what changed. */
class Rig(
    val playback: FakePlayback = FakePlayback(),
    val sleepTimer: FakeSleepTimer = FakeSleepTimer(),
    val tempo: FakeTempo = FakeTempo(),
    val crossfade: FakeCrossfade = FakeCrossfade(),
    val library: LibraryRepository = FakeLibrary(),
    val playlists: FakePlaylists = FakePlaylists(),
    val djMode: FakeDjMode = FakeDjMode(),
) {
    val ports = ApiPorts(playback, sleepTimer, tempo, crossfade, library, playlists, djMode)
    val dispatcher = ApiDispatcher(ports)
    val queries = ApiQueryEngine(ports)
}
