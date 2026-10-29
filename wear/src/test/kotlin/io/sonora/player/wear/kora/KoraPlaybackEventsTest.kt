package io.sonora.player.wear.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.media.PlaybackStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The clock is read once per observed state (state changes) or once per announcement (now playing), in order. */
class KoraPlaybackEventsTest {

    private fun clockOf(vararg times: Long): () -> Long {
        val remaining = ArrayDeque(times.toList())
        return { remaining.removeFirst() }
    }

    private suspend fun stateChanges(times: List<Long>, vararg states: WatchPlayerState) =
        flowOf(*states).playbackStateChanges(clockOf(*times.toLongArray())).toList()

    private suspend fun nowPlaying(times: List<Long>, vararg states: WatchPlayerState) =
        flowOf(*states).nowPlayingChanges(clockOf(*times.toLongArray())).toList()

    private val one = song("1")
    private val two = song("2")

    @Test
    fun `the first state is always reported`() = runTest {
        val events = stateChanges(listOf(10), playerState(one))

        assertThat(events).hasSize(1)
        assertThat(events.single().status).isEqualTo(PlaybackStatus.PLAYING)
        assertThat(events.single().updatedAtEpochMs).isEqualTo(10)
    }

    @Test
    fun `a repeated snapshot that predicts its own position is not reported`() = runTest {
        val events = stateChanges(
            listOf(0, 250, 500, 1_000),
            playerState(one, positionMs = 0),
            playerState(one, positionMs = 250),
            playerState(one, positionMs = 500),
            playerState(one, positionMs = 1_000),
        )

        assertThat(events).hasSize(1)
    }

    @Test
    fun `a pause is reported with the position it stopped at`() = runTest {
        val events = stateChanges(
            listOf(0, 100),
            playerState(one, WatchStatus.PLAYING, positionMs = 1_000),
            playerState(one, WatchStatus.PAUSED, positionMs = 1_100),
        )

        assertThat(events.map { it.status }).containsExactly(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED).inOrder()
        assertThat(events.last().positionMs).isEqualTo(1_100)
        assertThat(events.last().updatedAtEpochMs).isEqualTo(100)
    }

    @Test
    fun `a new track and a shuffle change are reported`() = runTest {
        val events = stateChanges(
            listOf(0, 10, 20),
            playerState(one),
            playerState(two),
            playerState(two, shuffle = true),
        )

        assertThat(events.map { it.track?.id }).containsExactly(koraTrackId("1"), koraTrackId("2"), koraTrackId("2")).inOrder()
        assertThat(events.last().shuffle).isTrue()
    }

    @Test
    fun `the queue ending is reported`() = runTest {
        val events = stateChanges(listOf(0, 10), playerState(one), playerState(one, WatchStatus.ENDED, positionMs = 10))

        assertThat(events.last().status).isEqualTo(PlaybackStatus.ENDED)
    }

    @Test
    fun `a seek past a second from where the position was heading is reported`() = runTest {
        val events = stateChanges(
            listOf(0, 1_000, 2_000),
            playerState(one, positionMs = 0),
            playerState(one, positionMs = 1_000), // exactly on course: nothing
            playerState(one, positionMs = 30_000), // a seek
        )

        assertThat(events.map { it.positionMs }).containsExactly(0L, 30_000L).inOrder()
    }

    @Test
    fun `a paused track is not expected to move`() = runTest {
        val events = stateChanges(
            listOf(0, 5_000, 6_000),
            playerState(one, WatchStatus.PAUSED, positionMs = 2_000),
            playerState(one, WatchStatus.PAUSED, positionMs = 2_000), // five seconds later, same place
            playerState(one, WatchStatus.PAUSED, positionMs = 8_000), // seeked while paused
        )

        assertThat(events.map { it.positionMs }).containsExactly(2_000L, 8_000L).inOrder()
    }

    @Test
    fun `a track restarting by itself is a jump`() = runTest {
        val events = stateChanges(
            listOf(0, 180_000),
            playerState(one, positionMs = 179_900),
            playerState(one, positionMs = 0),
        )

        assertThat(events).hasSize(2)
    }

    @Test
    fun `the track already playing is not announced as new`() = runTest {
        assertThat(nowPlaying(emptyList(), playerState(one), playerState(one, WatchStatus.PAUSED))).isEmpty()
    }

    @Test
    fun `a track that becomes current is announced once with the time it started`() = runTest {
        val announced = nowPlaying(listOf(77), playerState(one), playerState(two), playerState(two, positionMs = 5))

        assertThat(announced).hasSize(1)
        assertThat(announced.single().track.id).isEqualTo(koraTrackId("2"))
        assertThat(announced.single().startedAtEpochMs).isEqualTo(77)
    }

    @Test
    fun `a track after an empty player is announced`() = runTest {
        val announced = nowPlaying(listOf(5), playerState(null, WatchStatus.IDLE), playerState(one))

        assertThat(announced.map { it.track.id }).containsExactly(koraTrackId("1"))
    }

    @Test
    fun `the player emptying announces nothing`() = runTest {
        assertThat(nowPlaying(emptyList(), playerState(one), playerState(null, WatchStatus.IDLE))).isEmpty()
    }
}
