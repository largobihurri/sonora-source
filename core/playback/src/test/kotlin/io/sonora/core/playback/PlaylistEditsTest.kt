package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.QueueChange
import kotlin.random.Random
import org.junit.Test

/**
 * Drives the edits of real queue operations through [applyTo] into a fake playlist with Media3's index semantics
 * (the current item follows its entry; moves, removals and replacements around it never interrupt it). The fake
 * ending up equal to the queue proves the edit stream is what the player needs.
 */
class PlaylistEditsTest {

    /** List-backed [PlaylistTarget]; fails if an edit would remove or replace the current item. */
    private class FakePlaylist : PlaylistTarget {
        val items = mutableListOf<QueueItem>()
        var currentIndex = -1
        val calls = mutableListOf<String>()

        override fun setAll(items: List<QueueItem>, startIndex: Int) {
            calls += "setAll"
            this.items.clear()
            this.items += items
            currentIndex = startIndex
        }

        override fun insert(index: Int, items: List<QueueItem>) {
            calls += "insert"
            this.items.addAll(index, items)
            if (index <= currentIndex) currentIndex += items.size
        }

        override fun move(from: Int, to: Int) {
            calls += "move"
            val current = items[currentIndex]
            items.add(to, items.removeAt(from))
            currentIndex = items.indexOf(current)
        }

        override fun remove(from: Int, toExclusive: Int) {
            calls += "remove"
            check(currentIndex !in from until toExclusive) { "removed the current item" }
            repeat(toExclusive - from) { items.removeAt(from) }
            if (currentIndex >= toExclusive) currentIndex -= toExclusive - from
        }

        override fun replace(from: Int, toExclusive: Int, items: List<QueueItem>) {
            calls += "replace"
            check(currentIndex !in from until toExclusive) { "replaced the current item" }
            repeat(toExclusive - from) { this.items.removeAt(from) }
            this.items.addAll(from, items)
            if (currentIndex >= toExclusive) currentIndex += items.size - (toExclusive - from)
        }

        override fun skipTo(index: Int) {
            calls += "skipTo"
            currentIndex = index
        }

        override fun update(index: Int, item: QueueItem) {
            calls += "update"
            check(items[index].id == item.id) { "updated another entry" }
            items[index] = item
        }
    }

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", title = "T$id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    private val fake = FakePlaylist()

    /** Applies [change] to the fake and checks the fake now mirrors the queue. */
    private fun apply(change: QueueChange): PlayQueue {
        change.edits.applyTo(fake)
        assertThat(fake.items).isEqualTo(change.queue.items)
        assertThat(fake.currentIndex).isEqualTo(change.queue.currentIndex)
        return change.queue
    }

    @Test
    fun `every queue operation leaves the player playlist equal to the queue`() {
        var queue = apply(PlayQueue().play((1L..6L).map(::track), startIndex = 2))
        queue = apply(queue.playNext(listOf(track(7), track(8))))
        queue = apply(queue.addToQueue(listOf(track(9))))
        queue = apply(queue.move(from = 8, to = 4))
        queue = apply(queue.remove(0))
        queue = apply(queue.setShuffle(true, Random(5)))
        queue = apply(queue.playNow(listOf(track(10))))
        queue = apply(queue.addToQueue(listOf(track(1))))
        queue = apply(queue.skipTo(4))
        queue = apply(queue.setShuffle(false))
        queue = apply(queue.clearUpcoming())

        assertThat(queue.items.last().id).isEqualTo(queue.current?.id)
    }

    @Test
    fun `play now inserts and then skips`() {
        val queue = apply(PlayQueue().play((1L..3L).map(::track)))
        fake.calls.clear()

        apply(queue.playNow(listOf(track(9))))

        assertThat(fake.calls).containsExactly("insert", "skipTo").inOrder()
    }

    @Test
    fun `shuffle never touches the playing item and uses at most two bulk edits`() {
        val queue = apply(PlayQueue().play((1L..50L).map(::track), startIndex = 20))
        fake.calls.clear()

        val shuffled = apply(queue.setShuffle(true, Random(1)))
        apply(shuffled.setShuffle(false))

        assertThat(fake.calls.size).isAtMost(4)
        assertThat(fake.calls.toSet()).containsExactly("replace")
    }

    @Test
    fun `emulator sequence keeps every track - album, add album, shuffle, remove, skip, move`() {
        var queue = apply(PlayQueue().playNow((1L..24L).map(::track)))
        queue = apply(queue.addToQueue((101L..104L).map(::track)))
        queue = apply(queue.setShuffle(true, Random(42)))
        queue = apply(queue.remove(queue.currentIndex + 1))
        queue = apply(queue.skipTo(queue.currentIndex + 2))
        queue = apply(queue.move(queue.currentIndex + 2, queue.currentIndex + 5))

        assertThat(fake.items).hasSize(27)
        assertThat(fake.items.map { it.id }.toSet()).hasSize(27)
    }
}
