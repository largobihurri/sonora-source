package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.media.PlaybackStatus
import io.sonora.core.domain.model.PlaybackState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The clock is read once per observed state (state changes) or once per emission (now playing), in order. */
class KoraPlaybackEventsTest {

    private fun clockOf(vararg times: Long): () -> Long {
        val remaining = ArrayDeque(times.toList())
        return { remaining.removeFirst() }
    }

    private suspend fun stateChanges(times: List<Long>, vararg states: PlaybackState) =
        flowOf(*states).playbackStateChanges(clockOf(*times.toLongArray())).toList()

    @Test
    fun `the first state is always reported`() = runTest {
        val events = stateChanges(listOf(10), playing(track(1), isPlaying = true))

        assertThat(events).hasSize(1)
        assertThat(events.single().status).isEqualTo(PlaybackStatus.PLAYING)
        assertThat(events.single().updatedAtEpochMs).isEqualTo(10)
    }

    @Test
    fun `position ticks of a playing track are not reported`() = runTest {
        val events = stateChanges(
            listOf(0, 250, 500, 750),
            playing(track(1), positionMs = 0),
            playing(track(1), positionMs = 250),
            playing(track(1), positionMs = 500),
            playing(track(1), positionMs = 750),
        )

        assertThat(events).hasSize(1)
    }

    @Test
    fun `a pause is reported`() = runTest {
        val events = stateChanges(
            listOf(0, 100),
            playing(track(1), isPlaying = true, positionMs = 1_000),
            playing(track(1), isPlaying = false, positionMs = 1_100),
        )

        assertThat(events.map { it.status }).containsExactly(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED).inOrder()
        assertThat(events.last().positionMs).isEqualTo(1_100)
        assertThat(events.last().updatedAtEpochMs).isEqualTo(100)
    }

    @Test
    fun `a new track is reported`() = runTest {
        val events = stateChanges(listOf(0, 10), playing(track(1)), playing(track(2)))

        assertThat(events.map { it.track?.id }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `shuffle and the player connecting are reported`() = runTest {
        val events = stateChanges(
            listOf(0, 10, 20),
            playing(null, isPlaying = false, connected = false),
            playing(null, isPlaying = false, connected = true),
            playing(null, isPlaying = false, connected = true, shuffle = true),
        )

        assertThat(events).hasSize(3)
        assertThat(events.last().shuffle).isTrue()
    }

    @Test
    fun `a repeat mode change alone adds nothing to the reported state`() = runTest {
        val events = stateChanges(
            listOf(0, 10),
            playing(track(1)),
            playing(track(1)).copy(repeatMode = io.sonora.core.domain.model.RepeatMode.ALL),
        )

        assertThat(events).hasSize(1)
    }

    @Test
    fun `a seek while playing is reported`() = runTest {
        val events = stateChanges(
            listOf(0, 250, 500),
            playing(track(1), positionMs = 10_000),
            playing(track(1), positionMs = 10_250),
            playing(track(1), positionMs = 90_000),
        )

        assertThat(events.map { it.positionMs }).containsExactly(10_000L, 90_000L).inOrder()
    }

    @Test
    fun `a seek while paused is reported`() = runTest {
        val events = stateChanges(
            listOf(0, 5_000),
            playing(track(1), isPlaying = false, positionMs = 10_000),
            playing(track(1), isPlaying = false, positionMs = 30_000),
        )

        assertThat(events.map { it.positionMs }).containsExactly(10_000L, 30_000L).inOrder()
    }

    @Test
    fun `a slow ticker is not mistaken for a seek`() = runTest {
        val events = stateChanges(
            listOf(0, 2_000),
            playing(track(1), positionMs = 10_000),
            playing(track(1), positionMs = 12_000),
        )

        assertThat(events).hasSize(1)
    }

    @Test
    fun `a repeat one restart of the same track is reported`() = runTest {
        val events = stateChanges(
            listOf(0, 250),
            playing(track(1), positionMs = 179_900),
            playing(track(1), positionMs = 20),
        )

        assertThat(events).hasSize(2)
    }

    private suspend fun nowPlaying(vararg states: PlaybackState, now: Long = 77) =
        flowOf(*states).nowPlayingChanges(clockOf(*LongArray(states.size) { now + it })).toList()

    @Test
    fun `the track already playing when the first state arrives is not announced`() = runTest {
        assertThat(nowPlaying(playing(track(1)))).isEmpty()
    }

    @Test
    fun `a track that becomes current is announced with the time it started`() = runTest {
        val events = nowPlaying(playing(track(1)), playing(track(2)), now = 500)

        assertThat(events).hasSize(1)
        assertThat(events.single().track.id).isEqualTo("2")
        assertThat(events.single().track.title).isEqualTo("Track 2")
        assertThat(events.single().startedAtEpochMs).isEqualTo(500)
    }

    @Test
    fun `pausing, ticking and shuffling do not announce the same track again`() = runTest {
        val events = nowPlaying(
            playing(track(1)),
            playing(track(1), positionMs = 250),
            playing(track(1), isPlaying = false),
            playing(track(1), shuffle = true),
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `a first track after an empty connected player is announced`() = runTest {
        val events = nowPlaying(playing(null, isPlaying = false), playing(track(4)))

        assertThat(events.map { it.track.id }).containsExactly("4")
    }

    @Test
    fun `a disconnected player announces nothing and a reconnect is not a new track`() = runTest {
        val events = nowPlaying(
            playing(null, isPlaying = false, connected = false),
            playing(track(1), connected = true),
            playing(null, isPlaying = false, connected = false),
            playing(track(1), connected = true),
        )

        assertThat(events).isEmpty()
    }

    @Test
    fun `the queue ending does not announce anything and the next play does`() = runTest {
        val events = nowPlaying(playing(track(1)), playing(null, isPlaying = false), playing(track(2)))

        assertThat(events.map { it.track.id }).containsExactly("2")
    }
}
