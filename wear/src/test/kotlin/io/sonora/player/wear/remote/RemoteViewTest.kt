package io.sonora.player.wear.remote

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.remote.RemoteQueueItem
import io.sonora.core.watchsync.remote.RemoteState
import io.sonora.core.watchsync.remote.RemoteTrack
import org.junit.Test

class RemoteViewTest {
    private val track = RemoteTrack(id = 102, title = "Song", artist = "Band", album = "Record", durationMs = 200_000)

    private fun state(queue: List<RemoteQueueItem>? = null, index: Int = 2, size: Int = 5) = RemoteState(
        track = track, isPlaying = true, positionMs = 1_000, queueIndex = index, queueSize = size, hasNext = true, queue = queue,
    )

    @Test
    fun `the playing song becomes what Now Playing shows`() {
        val ui = state().toPlaybackUiState()!!

        assertThat(ui.connected).isTrue()
        assertThat(ui.mediaId).isEqualTo("102")
        assertThat(ui.title).isEqualTo("Song")
        assertThat(ui.artist).isEqualTo("Band · Record")
        assertThat(ui.playWhenReady).isTrue()
        assertThat(ui.waitingForOutput).isFalse()
        assertThat(ui.hasNext).isTrue()
        assertThat(ui.durationMs).isEqualTo(200_000)
    }

    @Test
    fun `nothing playing on the phone maps to no song`() {
        assertThat(RemoteState().toPlaybackUiState()).isNull()
    }

    @Test
    fun `an unknown duration is not a duration`() {
        assertThat(state().copy(track = track.copy(durationMs = 0)).toPlaybackUiState()!!.durationMs).isNull()
    }

    @Test
    fun `the subtitle joins what is known of artist and album`() {
        assertThat(remoteSubtitle("Band", "Record")).isEqualTo("Band · Record")
        assertThat(remoteSubtitle("Band", "")).isEqualTo("Band")
        assertThat(remoteSubtitle("", "Record")).isEqualTo("Record")
        assertThat(remoteSubtitle("Band", "Band")).isEqualTo("Band")
        assertThat(remoteSubtitle("", "")).isEqualTo("")
    }

    @Test
    fun `the phone's queue window becomes the same rows the watch's own queue shows`() {
        val window = listOf(RemoteQueueItem(102, "Song", "Band"), RemoteQueueItem(103, "Next", "Other"), RemoteQueueItem(104, "Last", ""))

        val rows = remoteQueueUiState(state(window)).entries

        assertThat(rows.map { it.mediaId }).containsExactly("102", "103", "104").inOrder()
        assertThat(rows.map { it.title }).containsExactly("Song", "Next", "Last").inOrder()
        assertThat(rows.map { it.isCurrent }).containsExactly(true, false, false).inOrder()
    }

    @Test
    fun `rows carry their place in the phone's whole queue, not in the window`() {
        val window = listOf(RemoteQueueItem(102, "Song", "Band"), RemoteQueueItem(103, "Next", "Other"))

        val rows = remoteQueueUiState(state(window, index = 2, size = 4)).entries

        assertThat(rows.map { it.index }).containsExactly(2, 3).inOrder()
    }

    @Test
    fun `only upcoming songs move and the playing song cannot be removed`() {
        val window = listOf(RemoteQueueItem(102, "A", ""), RemoteQueueItem(103, "B", ""), RemoteQueueItem(104, "C", ""))

        val rows = remoteQueueUiState(state(window, index = 2, size = 6)).entries

        assertThat(rows.map { it.canRemove }).containsExactly(false, true, true).inOrder()
        assertThat(rows.map { it.canMoveUp }).containsExactly(false, false, true).inOrder()
        assertThat(rows.map { it.canMoveDown }).containsExactly(false, true, true).inOrder() // C is not last: the queue goes on
    }

    @Test
    fun `the last song of a queue cannot move down`() {
        val window = listOf(RemoteQueueItem(102, "A", ""), RemoteQueueItem(103, "B", ""))

        val rows = remoteQueueUiState(state(window, index = 2, size = 4)).entries

        assertThat(rows.last().canMoveDown).isFalse()
    }

    @Test
    fun `without a queue window there are no rows`() {
        assertThat(remoteQueueUiState(state(queue = null)).entries).isEmpty()
    }

    @Test
    fun `a row names its phone queue entry for the commands`() {
        val row = remoteQueueUiState(state(listOf(RemoteQueueItem(102, "A", "")))).entries.single()

        assertThat(row.remoteId()).isEqualTo(102L)
        assertThat(row.copy(mediaId = "not a number").remoteId()).isNull()
    }
}
