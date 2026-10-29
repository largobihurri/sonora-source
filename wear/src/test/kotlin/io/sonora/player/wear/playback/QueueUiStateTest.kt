package io.sonora.player.wear.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.core.watchsync.remote.moveTarget
import org.junit.Test

class QueueUiStateTest {

    private fun items(vararg ids: String) = ids.map { QueueItem(mediaId = it, title = "Title $it", artist = "Artist $it") }

    @Test
    fun `an empty timeline gives an empty queue`() {
        assertThat(queueUiState(emptyList(), currentIndex = 0).entries).isEmpty()
        assertThat(queueUiState(emptyList(), currentIndex = -1).entries).isEmpty()
    }

    @Test
    fun `the queue starts at the current song and lists the upcoming ones`() {
        val state = queueUiState(items("a", "b", "c", "d"), currentIndex = 1)

        assertThat(state.entries.map { it.mediaId }).containsExactly("b", "c", "d").inOrder()
        assertThat(state.entries.map { it.index }).containsExactly(1, 2, 3).inOrder()
        assertThat(state.entries.map { it.isCurrent }).containsExactly(true, false, false).inOrder()
    }

    @Test
    fun `an entry carries the item's title and artist`() {
        val entry = queueUiState(
            listOf(QueueItem(mediaId = "a", title = "Song", artist = "Band"), QueueItem(mediaId = "b", title = null, artist = null)),
            currentIndex = 0,
        ).entries

        assertThat(entry[0].title).isEqualTo("Song")
        assertThat(entry[0].artist).isEqualTo("Band")
        assertThat(entry[1].title).isEmpty()
        assertThat(entry[1].artist).isNull()
    }

    @Test
    fun `an unknown current index is clamped into the timeline`() {
        assertThat(queueUiState(items("a", "b"), currentIndex = -1).entries.map { it.mediaId }).containsExactly("a", "b").inOrder()
        assertThat(queueUiState(items("a", "b"), currentIndex = 9).entries.map { it.mediaId }).containsExactly("b")
    }

    @Test
    fun `the same song queued twice gets distinct keys`() {
        val entries = queueUiState(items("a", "b", "a", "a"), currentIndex = 0).entries

        assertThat(entries.map { it.key }.toSet()).hasSize(4)
        assertThat(entries.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `the current song cannot be moved`() {
        val current = queueUiState(items("a", "b", "c"), currentIndex = 0).entries.first()

        assertThat(current.canMoveUp).isFalse()
        assertThat(current.canMoveDown).isFalse()
    }

    @Test
    fun `the song right after the current one can only move down`() {
        val entry = queueUiState(items("a", "b", "c"), currentIndex = 0).entries[1]

        assertThat(entry.canMoveUp).isFalse()
        assertThat(entry.canMoveDown).isTrue()
    }

    @Test
    fun `a song in the middle moves both ways and the last one only up`() {
        val entries = queueUiState(items("a", "b", "c", "d"), currentIndex = 0).entries

        assertThat(entries[2].canMoveUp).isTrue()
        assertThat(entries[2].canMoveDown).isTrue()
        assertThat(entries[3].canMoveUp).isTrue()
        assertThat(entries[3].canMoveDown).isFalse()
    }

    @Test
    fun `moving up goes one place towards the current song but never past it`() {
        assertThat(moveTarget(QueueDirection.Up, index = 3, currentIndex = 0, count = 5)).isEqualTo(2)
        assertThat(moveTarget(QueueDirection.Up, index = 2, currentIndex = 0, count = 5)).isEqualTo(1)
        assertThat(moveTarget(QueueDirection.Up, index = 1, currentIndex = 0, count = 5)).isNull()
        assertThat(moveTarget(QueueDirection.Up, index = 0, currentIndex = 0, count = 5)).isNull()
    }

    @Test
    fun `moving down goes one place and stops at the end`() {
        assertThat(moveTarget(QueueDirection.Down, index = 1, currentIndex = 0, count = 5)).isEqualTo(2)
        assertThat(moveTarget(QueueDirection.Down, index = 3, currentIndex = 0, count = 5)).isEqualTo(4)
        assertThat(moveTarget(QueueDirection.Down, index = 4, currentIndex = 0, count = 5)).isNull()
    }

    @Test
    fun `the current song and songs outside the timeline have no move target`() {
        assertThat(moveTarget(QueueDirection.Down, index = 2, currentIndex = 2, count = 5)).isNull()
        assertThat(moveTarget(QueueDirection.Down, index = 7, currentIndex = 0, count = 5)).isNull()
        assertThat(moveTarget(QueueDirection.Up, index = -1, currentIndex = 0, count = 5)).isNull()
    }

    @Test
    fun `songs before the current one are not offered for moving`() {
        assertThat(moveTarget(QueueDirection.Down, index = 0, currentIndex = 2, count = 5)).isNull()
        assertThat(moveTarget(QueueDirection.Up, index = 1, currentIndex = 2, count = 5)).isNull()
    }

    // The stale-timeline guard behind jump, remove and move.

    private fun entryAt(index: Int, mediaId: String) = QueueEntry(
        key = "$mediaId#1", index = index, mediaId = mediaId, title = "", artist = null,
        isCurrent = false, canMoveUp = false, canMoveDown = false,
    )

    private val timeline = listOf("a", "b", "c")

    @Test
    fun `an entry still at its place in the timeline matches`() {
        assertThat(entryAt(1, "b").isAt(timeline.size) { timeline[it] }).isTrue()
        assertThat(entryAt(0, "a").isAt(timeline.size) { timeline[it] }).isTrue()
        assertThat(entryAt(2, "c").isAt(timeline.size) { timeline[it] }).isTrue()
    }

    @Test
    fun `an entry past the end or before the start of the timeline does not match and reads nothing`() {
        val read = mutableListOf<Int>()
        val mediaIdAt = { index: Int -> read += index; timeline[index] }

        assertThat(entryAt(3, "c").isAt(timeline.size, mediaIdAt)).isFalse()
        assertThat(entryAt(-1, "a").isAt(timeline.size, mediaIdAt)).isFalse()
        assertThat(entryAt(0, "a").isAt(0, mediaIdAt)).isFalse()
        assertThat(read).isEmpty()
    }

    @Test
    fun `an entry whose song is no longer at its place does not match`() {
        // The queue changed under the screen: "b" was removed, so index 1 now holds "c".
        val changed = listOf("a", "c")

        assertThat(entryAt(1, "b").isAt(changed.size) { changed[it] }).isFalse()
        // The same song queued twice: the entry only matches the place it was listed at.
        val twice = listOf("a", "b", "b")
        assertThat(entryAt(1, "b").isAt(twice.size) { twice[it] }).isTrue()
        assertThat(entryAt(0, "b").isAt(twice.size) { twice[it] }).isFalse()
    }
}
