package io.sonora.core.watchphone.remote

import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.watchsync.WatchNode
import io.sonora.core.watchsync.remote.RemoteLink
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun track(id: Long, title: String = "Song $id", artist: String = "Artist", album: String = "Album", durationMs: Long = 200_000) = Track(
    id = id, uri = "content://media/$id", title = title, artist = artist, album = album, albumArtist = null,
    durationMs = durationMs, trackNumber = null, discNumber = null, year = null, albumId = 1,
    artworkUri = null, mimeType = "audio/flac", sizeBytes = 10,
)

/** Queue items whose ids are 100 + position, so an id never equals an index. */
fun queueOf(count: Int): List<QueueItem> = (0 until count).map { QueueItem(100L + it, track(it.toLong())) }

fun playbackState(count: Int, current: Int, playing: Boolean = true, positionMs: Long = 5_000) = PlaybackState(
    currentTrack = if (current in 0 until count) track(current.toLong()) else null,
    queue = queueOf(count),
    isPlaying = playing,
    positionMs = positionMs,
    durationMs = 200_000,
    currentIndex = current,
    isConnected = true,
)

/** Records every call; only the commands the remote uses are implemented. */
class FakePlayback(initial: PlaybackState = PlaybackState()) : PlaybackController {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<PlaybackState> = mutableState
    val calls = CopyOnWriteArrayList<String>()

    fun emit(state: PlaybackState) {
        mutableState.value = state
    }

    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun skipToNext() { calls += "next" }
    override fun skipToPrevious() { calls += "previous" }
    override fun skipToQueueItem(index: Int) { calls += "skipTo($index)" }
    override fun removeQueueItem(index: Int) { calls += "remove($index)" }
    override fun moveQueueItem(from: Int, to: Int) { calls += "move($from,$to)" }

    override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) = unexpected("playQueue")
    override fun playNow(tracks: List<Track>) = unexpected("playNow")
    override fun playNext(tracks: List<Track>) = unexpected("playNext")
    override fun addToQueue(tracks: List<Track>) = unexpected("addToQueue")
    override fun clearUpcoming() = unexpected("clearUpcoming")
    override fun togglePlayPause() = unexpected("togglePlayPause")
    override fun seekTo(positionMs: Long) = unexpected("seekTo")
    override fun setShuffle(enabled: Boolean) = unexpected("setShuffle")
    override fun setRepeatMode(mode: RepeatMode) = unexpected("setRepeatMode")
    override fun cycleRepeatMode() = unexpected("cycleRepeatMode")

    private fun unexpected(name: String): Nothing = throw AssertionError("The remote must not call $name")
}

class FakeVolume(var current: Int = 7, val max: Int = 15) : RemoteVolume {
    val steps = CopyOnWriteArrayList<Int>()

    override fun level() = VolumeLevel(current, max)

    override fun step(steps: Int) {
        this.steps += steps
        current = (current + steps).coerceIn(0, max)
    }
}

class FakeLink(var watches: List<String> = listOf("watch")) : RemoteLink {
    val sent = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    var failSends = false
    var failLookup = false

    override suspend fun nodes(capability: String): List<WatchNode> {
        if (failLookup) throw IOException("Data Layer gone")
        return watches.map { WatchNode(it, it, isNearby = true) }
    }

    override suspend fun send(nodeId: String, bytes: ByteArray) {
        if (failSends) throw IOException("node gone")
        sent += nodeId to bytes
    }

    override fun listen(onMessage: (nodeId: String, bytes: ByteArray) -> Unit): AutoCloseable = AutoCloseable {}
}
