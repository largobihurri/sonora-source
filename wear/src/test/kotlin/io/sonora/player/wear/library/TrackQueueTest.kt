package io.sonora.player.wear.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackQueueTest {

    private fun track(id: String) = WatchTrack(id, "file:///$id", id, null, null, null, null)

    @Test
    fun `the queue is the whole list, starting at the tapped song`() {
        val tracks = listOf(track("a"), track("b"), track("c"))
        val queue = TrackQueue.startingAt(tracks, "b")
        assertThat(queue.tracks).isEqualTo(tracks)
        assertThat(queue.startIndex).isEqualTo(1)
    }

    @Test
    fun `a song that is gone starts the queue at the first one`() {
        assertThat(TrackQueue.startingAt(listOf(track("a"), track("b")), "gone").startIndex).isEqualTo(0)
    }

    @Test
    fun `an empty list starts at zero`() {
        assertThat(TrackQueue.startingAt(emptyList(), "a").startIndex).isEqualTo(0)
    }
}
