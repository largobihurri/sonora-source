package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.NoInput
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlayTracksRequest
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.core.KoraNode
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/** Goes through a real [KoraNode], so a handler or event source missing from the registration fails here. */
class MediaPlaybackHandlersTest {
    private val mainThread = Executors.newSingleThreadExecutor { Thread(it, "kora-test-main") }
    private val songs = listOf(track(1), track(2), track(3))
    private val player = FakePlayer(playing(songs[0], isPlaying = false, positionMs = 5_000))
    private val library = FakeLibrary(songs)
    private val playlists = FakePlaylists(listOf(playlist(10, "Mix", songs[0], songs[1], songs[2]), playlist(11, "Empty")))
    private val node = KoraNode("test.sonora").apply {
        val handlers = MediaPlaybackHandlers(
            playback = { player },
            library = { library },
            playlists = { playlists },
            mainDispatcher = mainThread.asCoroutineDispatcher(),
            clock = { 1_234L },
            connectWaitMs = 500,
        )
        provide(MediaPlayback) { handlers.register(this) }
    }

    @After
    fun tearDown() {
        mainThread.shutdownNow()
    }

    @Test
    fun `every transport command reaches the player on the main thread`() = runBlocking<Unit> {
        node.call(MediaPlayback.Play, NoInput)
        node.call(MediaPlayback.Pause, NoInput)
        node.call(MediaPlayback.Toggle, NoInput)
        node.call(MediaPlayback.Next, NoInput)
        node.call(MediaPlayback.Previous, NoInput)
        node.call(MediaPlayback.SeekTo, SeekRequest(9_000))

        assertThat(player.calls).containsExactly("play", "pause", "toggle", "next", "previous", "seekTo 9000").inOrder()
        assertThat(player.threads.toSet()).containsExactly("kora-test-main")
    }

    @Test
    fun `a negative seek is an invalid payload and moves nothing`() = runBlocking<Unit> {
        val failure = koraFailure { node.call(MediaPlayback.SeekTo, SeekRequest(-1)) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `a playlist replaces the queue from the requested index`() = runBlocking<Unit> {
        node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest("10", startIndex = 1))

        assertThat(player.calls).containsExactly("playQueue [1, 2, 3] start=1 shuffle=false")
        assertThat(player.threads.toSet()).containsExactly("kora-test-main")
    }

    @Test
    fun `a playlist can be played shuffled`() = runBlocking<Unit> {
        node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest("10", shuffle = true))

        assertThat(player.calls).containsExactly("playQueue [1, 2, 3] start=0 shuffle=true")
    }

    @Test
    fun `playing a playlist that is unknown, empty or badly numbered fails without touching the player`() = runBlocking<Unit> {
        listOf("99", "11", "abc").forEach { id ->
            val failure = koraFailure { node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest(id)) }
            assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        }
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `a start index outside the playlist is an invalid payload`() = runBlocking<Unit> {
        listOf(-1, 3).forEach { start ->
            val failure = koraFailure { node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest("10", startIndex = start)) }
            assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        }
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `tracks are played in the order asked, repeats included`() = runBlocking<Unit> {
        node.call(MediaPlayback.PlayTracks, PlayTracksRequest(listOf("3", "1", "3"), startIndex = 2))

        assertThat(player.calls).containsExactly("playQueue [3, 1, 3] start=2 shuffle=false")
    }

    @Test
    fun `64-bit and negative track ids are played`() = runBlocking<Unit> {
        library.all = songs + track(7_636_189_893_023_974_685) + track(-1)

        node.call(MediaPlayback.PlayTracks, PlayTracksRequest(listOf("7636189893023974685", "-1")))

        assertThat(player.calls).containsExactly("playQueue [7636189893023974685, -1] start=0 shuffle=false")
    }

    @Test
    fun `an unknown track fails the whole request without touching the player`() = runBlocking<Unit> {
        val failure = koraFailure { node.call(MediaPlayback.PlayTracks, PlayTracksRequest(listOf("1", "42"))) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `an empty, oversized or badly indexed track list is an invalid payload`() = runBlocking<Unit> {
        val bad = listOf(
            PlayTracksRequest(emptyList()),
            PlayTracksRequest(List(501) { "1" }),
            PlayTracksRequest(listOf("1"), startIndex = 1),
            PlayTracksRequest(listOf("1"), startIndex = -1),
            PlayTracksRequest(listOf("file:///x")),
        )
        bad.forEach { request ->
            val failure = koraFailure { node.call(MediaPlayback.PlayTracks, request) }
            assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        }
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `the state is the player state with the capture time`() = runBlocking<Unit> {
        val state = node.call(MediaPlayback.GetState, NoInput)

        assertThat(state.status).isEqualTo(PlaybackStatus.PAUSED)
        assertThat(state.positionMs).isEqualTo(5_000)
        assertThat(state.updatedAtEpochMs).isEqualTo(1_234)
        assertThat(state.track?.id).isEqualTo("1")
    }

    @Test
    fun `a player that never connects is provider unavailable for reads and commands`() = runBlocking<Unit> {
        player.set(playing(null, isPlaying = false, connected = false))

        val read = koraFailure { node.call(MediaPlayback.GetState, NoInput) }
        val command = koraFailure { node.call(MediaPlayback.Play, NoInput) }
        val playlist = koraFailure { node.call(MediaPlayback.PlayPlaylist, PlayPlaylistRequest("10")) }

        assertThat(read.error.code).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE)
        assertThat(command.error.code).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE)
        assertThat(playlist.error.code).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE)
        assertThat(player.calls).isEmpty()
    }

    @Test
    fun `a player that connects late is waited for`() = runBlocking<Unit> {
        player.set(playing(null, isPlaying = false, connected = false))
        val reconnect = Thread { Thread.sleep(30); player.set(playing(songs[1], isPlaying = false)) }
        reconnect.start()

        val state = node.call(MediaPlayback.GetState, NoInput)

        assertThat(state.track?.id).isEqualTo("2")
    }

    @Test
    fun `the state event starts with the current state`() = runBlocking<Unit> {
        val first = node.events(MediaPlayback.PlaybackStateChanged).first()

        assertThat(first.status).isEqualTo(PlaybackStatus.PAUSED)
        assertThat(first.track?.id).isEqualTo("1")
    }

    @Test
    fun `the now playing event announces a track that becomes current after subscribing`() = runBlocking<Unit> {
        val announced = async(Dispatchers.Default) { node.events(MediaPlayback.NowPlayingChanged).first() }
        var next = 2L
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (!announced.isCompleted) {
                    player.set(playing(track(next++), isPlaying = true))
                    delay(5)
                }
            }
        }

        assertThat(announced.await().track.id.toLong()).isAtLeast(2L)
    }
}
