package io.sonora.core.kora

import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class FakeLibrary(var all: List<Track> = emptyList()) : LibraryRepository {
    override fun tracks(sort: TrackSort): Flow<List<Track>> = flowOf(LibraryQuery.sort(all, sort))
    override fun albums(): Flow<List<Album>> = flowOf(emptyList())
    override fun artists(): Flow<List<Artist>> = flowOf(emptyList())
    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = flowOf(emptyList())
    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> = flowOf(emptyList())
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> = flowOf(emptyList())
    override fun search(query: String): Flow<List<Track>> = flowOf(LibraryQuery.search(all, query))
}

internal class FakePlaylists(var all: List<PlaylistWithEntries> = emptyList()) : PlaylistRepository {
    override fun playlists(): Flow<List<PlaylistWithEntries>> = flowOf(all)
    override fun playlist(id: Long): Flow<PlaylistWithEntries?> = flowOf(all).map { list -> list.firstOrNull { it.playlist.id == id } }
    override suspend fun create(name: String, description: String, entries: List<PlaylistEntry>): Long = error("not used")
    override suspend fun updateDetails(id: Long, name: String, description: String) = error("not used")
    override suspend fun delete(id: Long) = error("not used")
    override suspend fun editEntries(id: Long, edit: (List<PlaylistEntry>) -> List<PlaylistEntry>) = error("not used")
}

/** Records every command with the thread it ran on; the state is whatever the test sets. */
internal class FakePlayer(initial: PlaybackState = playing(null, isPlaying = false)) : PlaybackController {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<PlaybackState> = mutable.asStateFlow()
    val calls = mutableListOf<String>()
    val threads = mutableListOf<String>()

    fun set(state: PlaybackState) {
        mutable.value = state
    }

    private fun record(call: String) {
        calls += call
        threads += Thread.currentThread().name.substringBefore(" @coroutine")
    }

    override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) =
        record("playQueue ${queue.map { it.id }} start=$startIndex shuffle=$shuffle")
    override fun playNow(tracks: List<Track>) = record("playNow")
    override fun playNext(tracks: List<Track>) = record("playNext")
    override fun addToQueue(tracks: List<Track>) = record("addToQueue")
    override fun moveQueueItem(from: Int, to: Int) = record("moveQueueItem")
    override fun removeQueueItem(index: Int) = record("removeQueueItem")
    override fun clearUpcoming() = record("clearUpcoming")
    override fun skipToQueueItem(index: Int) = record("skipToQueueItem")
    override fun play() = record("play")
    override fun pause() = record("pause")
    override fun togglePlayPause() = record("toggle")
    override fun skipToNext() = record("next")
    override fun skipToPrevious() = record("previous")
    override fun seekTo(positionMs: Long) = record("seekTo $positionMs")
    override fun setShuffle(enabled: Boolean) = record("setShuffle")
    override fun setRepeatMode(mode: RepeatMode) = record("setRepeatMode")
    override fun cycleRepeatMode() = record("cycleRepeatMode")
}

/** Runs [block], which must fail with a [KoraException]; returns it. */
internal suspend fun koraFailure(block: suspend () -> Unit): eus.bihurri.kora.contract.KoraException {
    try {
        block()
    } catch (e: eus.bihurri.kora.contract.KoraException) {
        return e
    }
    throw AssertionError("Expected a KoraException")
}

/** The play log as the database answers it: the window inclusive, oldest first, ties by id. Counts the batches asked. */
internal class FakeListeningHistory(var all: List<io.sonora.core.domain.stats.Listening> = emptyList()) :
    io.sonora.core.domain.stats.ListeningHistory {
    var batches = 0

    private fun window(fromMs: Long, toMs: Long) =
        all.filter { it.startedAtMs in fromMs..toMs }.sortedWith(compareBy({ it.startedAtMs }, { it.id }))

    override suspend fun record(listening: io.sonora.core.domain.stats.Listening) = error("not used")
    override fun perTrack(sinceMs: Long?) = error("not used")
    override fun firstStartedAtMs() = error("not used")
    override suspend fun startedBetween(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<io.sonora.core.domain.stats.Listening> {
        batches++
        return window(fromMs, toMs).drop(offset).take(limit)
    }
    override suspend fun countStartedBetween(fromMs: Long, toMs: Long): Int = window(fromMs, toMs).size
}
