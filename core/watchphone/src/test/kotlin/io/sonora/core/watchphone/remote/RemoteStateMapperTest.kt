package io.sonora.core.watchphone.remote

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.watchsync.remote.RemoteLimits
import io.sonora.core.watchsync.remote.RemoteQueueItem
import org.junit.Test

class RemoteStateMapperTest {
    private val volume = VolumeLevel(current = 7, max = 15)

    @Test
    fun `nothing playing maps to an empty state that still carries the volume`() {
        val state = remoteState(PlaybackState(), volume, includeQueue = true)

        assertThat(state.track).isNull()
        assertThat(state.isPlaying).isFalse()
        assertThat(state.volume).isEqualTo(7)
        assertThat(state.volumeMax).isEqualTo(15)
        assertThat(state.queueIndex).isEqualTo(-1)
        assertThat(state.queueSize).isEqualTo(0)
        assertThat(state.hasNext).isFalse()
        assertThat(state.queue).isEmpty()
    }

    @Test
    fun `the playing song takes its id from its queue entry`() {
        val state = remoteState(playbackState(count = 5, current = 2), volume, includeQueue = false)

        val track = state.track!!
        assertThat(track.id).isEqualTo(102)
        assertThat(track.title).isEqualTo("Song 2")
        assertThat(track.artist).isEqualTo("Artist")
        assertThat(track.album).isEqualTo("Album")
        assertThat(track.durationMs).isEqualTo(200_000)
        assertThat(state.isPlaying).isTrue()
        assertThat(state.positionMs).isEqualTo(5_000)
        assertThat(state.queueIndex).isEqualTo(2)
        assertThat(state.queueSize).isEqualTo(5)
        assertThat(state.hasNext).isTrue()
        assertThat(state.queue).isNull()
    }

    @Test
    fun `the last song has no next`() {
        assertThat(remoteState(playbackState(count = 3, current = 2), volume, includeQueue = false).hasNext).isFalse()
    }

    @Test
    fun `the queue window starts at the playing song and leaves out the played ones`() {
        val state = remoteState(playbackState(count = 5, current = 2), volume, includeQueue = true)

        assertThat(state.queue).containsExactly(
            RemoteQueueItem(102, "Song 2", "Artist"),
            RemoteQueueItem(103, "Song 3", "Artist"),
            RemoteQueueItem(104, "Song 4", "Artist"),
        ).inOrder()
    }

    @Test
    fun `a long queue is cut to the window size but keeps its real size`() {
        val state = remoteState(playbackState(count = 500, current = 10), volume, includeQueue = true)

        assertThat(state.queue).hasSize(RemoteLimits.MAX_QUEUE_ITEMS)
        assertThat(state.queue!!.first().id).isEqualTo(110)
        assertThat(state.queueSize).isEqualTo(500)
    }

    @Test
    fun `a current index outside the queue means no song, so no command can name one`() {
        val broken = playbackState(count = 3, current = 7)

        val state = remoteState(broken, volume, includeQueue = true)

        assertThat(state.track).isNull()
        assertThat(state.queueIndex).isEqualTo(-1)
        assertThat(state.queue).isEmpty()
    }

    @Test
    fun `an unknown duration falls back to the song's own`() {
        val unknown = playbackState(count = 1, current = 0).copy(durationMs = 0)

        assertThat(remoteState(unknown, volume, includeQueue = false).track!!.durationMs).isEqualTo(200_000)
    }

    @Test
    fun `a negative position is zero`() {
        val state = remoteState(playbackState(count = 1, current = 0, positionMs = -5), volume, includeQueue = false)

        assertThat(state.positionMs).isEqualTo(0)
    }
}
