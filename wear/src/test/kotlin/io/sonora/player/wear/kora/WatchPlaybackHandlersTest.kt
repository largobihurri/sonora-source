package io.sonora.player.wear.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlayTracksRequest
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.core.KoraNode
import kotlin.random.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test

/** Goes through a real [KoraNode], so a handler or event source missing from the registration fails here. */
class WatchPlaybackHandlersTest {
    private val songs = listOf(song("1.opus"), song("2.opus"), song("3.opus"))
    private val player = FakePlayerPort(playerState(songs[0], WatchStatus.PAUSED, positionMs = 5_000))
    private val node = KoraNode("test.watch").apply {
        val handlers = WatchPlaybackHandlers(player, songs = { songs }, clock = { 1_234L }, random = Random(3))
        provide(MediaPlayback) { handlers.register(this) }
    }

    private fun failureOf(block: suspend () -> Unit): KoraException = assertThrows(KoraException::class.java) { runBlocking { block() } }

    @Test
    fun `every transport command reaches the player`() = runBlocking<Unit> {
        node.call(MediaPlayback.Play, NoInput)
        node.call(MediaPlayback.Pause, NoInput)
        node.call(MediaPlayback.Toggle, NoInput)
        node.call(MediaPlayback.Next, NoInput)
        node.call(MediaPlayback.Previous, NoInput)
        node.call(MediaPlayback.SeekTo, SeekRequest(9_000))

        assertThat(player.calls).containsExactly("play", "pause", "toggle", "next", "previous", "seekTo 9000").inOrder()
    }

    @Test
    fun `a negative seek is an invalid payload and moves nothing`() {
        val failure = failureOf { node.call(MediaPlayback.SeekTo, SeekRequest(-1)) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `the state is the player's, stamped with the time of the call`() = runBlocking<Unit> {
        val state = node.call(MediaPlayback.GetState, NoInput)

        assertThat(state.status).isEqualTo(PlaybackStatus.PAUSED)
        assertThat(state.positionMs).isEqualTo(5_000)
        assertThat(state.updatedAtEpochMs).isEqualTo(1_234)
        assertThat(state.track?.id).isEqualTo(koraTrackId("1.opus"))
    }

    @Test
    fun `the watch's playlist replaces the queue from the requested index`() = runBlocking<Unit> {
        node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest(ALL_SONGS_ID, startIndex = 1))

        assertThat(player.calls).containsExactly("playQueue [1.opus, 2.opus, 3.opus] start=1")
    }

    @Test
    fun `the playlist can be played shuffled`() = runBlocking<Unit> {
        node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest(ALL_SONGS_ID, startIndex = 2, shuffle = true))

        val call = player.calls.single()
        assertThat(call).startsWith("playQueue [")
        assertThat(call).endsWith("start=0")
        assertThat(call).contains("1.opus")
        assertThat(call).contains("3.opus")
    }

    @Test
    fun `playing a playlist that is not the watch's, or an empty one, is an invalid payload`() {
        assertThat(failureOf { node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest("12")) }.error.code)
            .isEqualTo(ErrorCode.INVALID_PAYLOAD)

        val empty = KoraNode("test.empty").apply {
            val handlers = WatchPlaybackHandlers(player, songs = { emptyList() }, clock = { 0 })
            provide(MediaPlayback) { handlers.register(this) }
        }
        val failure = failureOf { empty.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest(ALL_SONGS_ID)) }
        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `a start outside the list plays nothing`() {
        val failure = failureOf { node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest(ALL_SONGS_ID, startIndex = 3)) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `tracks play in the order asked`() = runBlocking<Unit> {
        val ids = listOf(koraTrackId("3.opus"), koraTrackId("1.opus"))

        node.call(MediaPlayback.PlayTracks, PlayTracksRequest(ids, startIndex = 1))

        assertThat(player.calls).containsExactly("playQueue [3.opus, 1.opus] start=1")
    }

    @Test
    fun `bad track requests play nothing`() {
        val unknown = failureOf { node.call(MediaPlayback.PlayTracks, PlayTracksRequest(listOf("nope"))) }
        val empty = failureOf { node.call(MediaPlayback.PlayTracks, PlayTracksRequest(emptyList())) }
        val tooMany = failureOf { node.call(MediaPlayback.PlayTracks, PlayTracksRequest(List(501) { koraTrackId("1.opus") })) }

        listOf(unknown, empty, tooMany).forEach { assertThat(it.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD) }
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `an unreachable player is passed on as provider_unavailable`() {
        player.failure = KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "The player is not available"))

        assertThat(failureOf { node.call(MediaPlayback.Play, NoInput) }.error.code).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE)
        assertThat(failureOf { node.call(MediaPlayback.GetState, NoInput) }.error.code).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE)
    }

    private suspend fun until(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    @Test
    fun `state changes are delivered to a subscriber and only while it listens`() = runBlocking<Unit> {
        assertThat(player.activeSubscriptions).isEqualTo(0)
        val received = CopyOnWriteArrayList<PlaybackStatus>()
        val job = launch(Dispatchers.Default) { node.events(MediaPlayback.PlaybackStateChanged).collect { received += it.status } }

        // The first snapshot is always reported; then a change of status.
        until { received.size == 1 }
        player.emit(playerState(songs[0], WatchStatus.PLAYING, positionMs = 5_000))
        until { received.size == 2 }
        assertThat(received).containsExactly(PlaybackStatus.PAUSED, PlaybackStatus.PLAYING).inOrder()
        assertThat(player.activeSubscriptions).isEqualTo(1)

        job.cancelAndJoin()
        until { player.activeSubscriptions == 0 }
    }

    @Test
    fun `a new track is announced to a now playing subscriber`() = runBlocking<Unit> {
        val announced = CopyOnWriteArrayList<String>()
        val job = launch(Dispatchers.Default) { node.events(MediaPlayback.NowPlayingChanged).collect { announced += it.track.id } }

        until { player.activeSubscriptions == 1 }
        player.emit(playerState(songs[1], WatchStatus.PLAYING))
        until { announced.isNotEmpty() }
        job.cancelAndJoin()

        assertThat(announced).containsExactly(koraTrackId("2.opus"))
    }
}
