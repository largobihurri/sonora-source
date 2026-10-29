package io.sonora.core.watchsync.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StatePushPolicyTest {
    private val track = RemoteTrack(1, "Song", "Artist", "Album", durationMs = 200_000)
    private val playing = RemoteState(track = track, isPlaying = true, positionMs = 10_000, volume = 5, volumeMax = 15)

    @Test
    fun `the first state is always pushed`() {
        assertThat(StatePushPolicy.needsPush(previous = null, previousAt = 0, next = playing, now = 0)).isTrue()
    }

    @Test
    fun `the same state a moment later is not pushed again`() {
        val later = playing.copy(positionMs = 11_000)

        assertThat(StatePushPolicy.needsPush(playing, previousAt = 0, next = later, now = 1_000)).isFalse()
    }

    @Test
    fun `a change of song, play state, volume or queue is pushed`() {
        fun pushed(next: RemoteState) = StatePushPolicy.needsPush(playing, previousAt = 0, next = next, now = 0)

        assertThat(pushed(playing.copy(track = track.copy(id = 2)))).isTrue()
        assertThat(pushed(playing.copy(isPlaying = false))).isTrue()
        assertThat(pushed(playing.copy(volume = 6))).isTrue()
        assertThat(pushed(playing.copy(queueSize = 3))).isTrue()
        assertThat(pushed(playing.copy(hasNext = true))).isTrue()
        assertThat(pushed(playing.copy(queue = listOf(RemoteQueueItem(1, "A", ""))))).isTrue()
    }

    @Test
    fun `a seek is pushed because the position is far from where it should be`() {
        val seeked = playing.copy(positionMs = 100_000)

        assertThat(StatePushPolicy.needsPush(playing, previousAt = 0, next = seeked, now = 1_000)).isTrue()
    }

    @Test
    fun `a paused position that moved is pushed, one that did not is not`() {
        val paused = playing.copy(isPlaying = false)

        assertThat(StatePushPolicy.needsPush(paused, previousAt = 0, next = paused.copy(positionMs = 10_100), now = 5_000)).isFalse()
        assertThat(StatePushPolicy.needsPush(paused, previousAt = 0, next = paused.copy(positionMs = 40_000), now = 5_000)).isTrue()
    }
}
