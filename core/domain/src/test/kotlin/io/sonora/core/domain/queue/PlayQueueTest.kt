package io.sonora.core.domain.queue

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.queue.QueueEdit.Insert
import io.sonora.core.domain.queue.QueueEdit.Move
import io.sonora.core.domain.queue.QueueEdit.Remove
import io.sonora.core.domain.queue.QueueEdit.SetAll
import io.sonora.core.domain.queue.QueueEdit.SkipTo
import io.sonora.core.domain.queue.QueueEdit.Update
import kotlin.random.Random
import org.junit.Test

class PlayQueueTest {

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", title = "T$id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun tracks(vararg ids: Long) = ids.map(::track)

    /** Track ids in play order. */
    private val PlayQueue.trackIds get() = items.map { it.track.id }

    /** Track ids in the order restored when shuffle is turned off. */
    private val PlayQueue.unshuffledTrackIds get() = unshuffledIds.map { id -> items.first { it.id == id }.track.id }

    /** A queue of tracks 1..[size] playing [current], unshuffled. */
    private fun playing(size: Int, current: Int = 0): PlayQueue =
        PlayQueue().play(tracks(*LongArray(size) { it + 1L }), startIndex = current).queue

    @Test
    fun `play sets the queue in order from the start index`() {
        val change = PlayQueue().play(tracks(1, 2, 3), startIndex = 1)

        assertThat(change.queue.trackIds).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(change.queue.currentIndex).isEqualTo(1)
        assertThat(change.queue.shuffled).isFalse()
        assertThat(change.edits).containsExactly(SetAll(change.queue.items, 1))
    }

    @Test
    fun `play shuffled puts a random track first and keeps the given order to restore`() {
        val change = PlayQueue().play(tracks(1, 2, 3, 4, 5), shuffle = true, random = Random(7))
        val again = PlayQueue().play(tracks(1, 2, 3, 4, 5), shuffle = true, random = Random(7))

        assertThat(change.queue.currentIndex).isEqualTo(0)
        assertThat(change.queue.shuffled).isTrue()
        assertThat(change.queue.trackIds).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(change.queue.trackIds).isEqualTo(again.queue.trackIds)
        assertThat(change.queue.unshuffledTrackIds).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun `play with nothing to play changes nothing`() {
        val change = playing(2).play(emptyList())

        assertThat(change.edits).isEmpty()
        assertThat(change.queue).isEqualTo(playing(2))
    }

    @Test
    fun `play now inserts after the current track, plays it and keeps the rest`() {
        val change = playing(3, current = 1).playNow(tracks(9))

        assertThat(change.queue.trackIds).containsExactly(1L, 2L, 9L, 3L).inOrder()
        assertThat(change.queue.currentIndex).isEqualTo(2)
        assertThat(change.edits).containsExactly(Insert(2, listOf(change.queue.items[2])), SkipTo(2)).inOrder()
    }

    @Test
    fun `play next goes right after the current track, the latest first`() {
        val once = playing(3).playNext(tracks(8)).queue
        val twice = once.playNext(tracks(9)).queue

        assertThat(once.trackIds).containsExactly(1L, 8L, 2L, 3L).inOrder()
        assertThat(twice.trackIds).containsExactly(1L, 9L, 8L, 2L, 3L).inOrder()
        assertThat(twice.currentIndex).isEqualTo(0)
    }

    @Test
    fun `add to queue appends at the end`() {
        val change = playing(2).addToQueue(tracks(8, 9))

        assertThat(change.queue.trackIds).containsExactly(1L, 2L, 8L, 9L).inOrder()
        assertThat(change.edits).containsExactly(Insert(2, change.queue.items.takeLast(2)))
    }

    @Test
    fun `adding to an empty queue sets it up`() {
        val next = PlayQueue().playNext(tracks(1, 2))
        val end = PlayQueue().addToQueue(tracks(1, 2))
        val now = PlayQueue().playNow(tracks(1, 2))

        listOf(next, end, now).forEach {
            assertThat(it.queue.trackIds).containsExactly(1L, 2L).inOrder()
            assertThat(it.queue.currentIndex).isEqualTo(0)
            assertThat(it.edits).containsExactly(SetAll(it.queue.items, 0))
        }
    }

    @Test
    fun `shuffle on keeps the current track first and shuffles every other track`() {
        val change = playing(6, current = 2).setShuffle(true, Random(3))

        assertThat(change.queue.currentIndex).isEqualTo(0)
        assertThat(change.queue.current?.track?.id).isEqualTo(3L)
        assertThat(change.queue.trackIds).containsExactly(1L, 2L, 3L, 4L, 5L, 6L)
        assertThat(change.queue.trackIds).isEqualTo(playing(6, current = 2).setShuffle(true, Random(3)).queue.trackIds)
        assertThat(change.queue.shuffled).isTrue()
    }

    @Test
    fun `shuffle off restores the original order around the playing track`() {
        val shuffled = playing(6, current = 2).setShuffle(true, Random(3)).queue
        val playingFourth = shuffled.skipTo(shuffled.trackIds.indexOf(4L)).queue

        val restored = playingFourth.setShuffle(false).queue

        assertThat(restored.trackIds).containsExactly(1L, 2L, 3L, 4L, 5L, 6L).inOrder()
        assertThat(restored.currentIndex).isEqualTo(3)
        assertThat(restored.shuffled).isFalse()
    }

    @Test
    fun `tracks inserted while shuffled keep their place when shuffle is turned off`() {
        val shuffled = playing(5, current = 1).setShuffle(true, Random(11)).queue // playing track 2
        val withInserts = shuffled.playNext(tracks(8)).queue.addToQueue(tracks(9)).queue

        assertThat(withInserts.trackIds[1]).isEqualTo(8L)
        assertThat(withInserts.trackIds.last()).isEqualTo(9L)
        val restored = withInserts.setShuffle(false).queue
        assertThat(restored.trackIds).containsExactly(1L, 2L, 8L, 3L, 4L, 5L, 9L).inOrder()
        assertThat(restored.current?.track?.id).isEqualTo(2L)
    }

    @Test
    fun `play now while shuffled follows the track it interrupted when shuffle is turned off`() {
        val shuffled = playing(4, current = 0).setShuffle(true, Random(5)).queue // playing track 1
        val restored = shuffled.playNow(tracks(9)).queue.setShuffle(false).queue

        assertThat(restored.trackIds).containsExactly(1L, 9L, 2L, 3L, 4L).inOrder()
        assertThat(restored.current?.track?.id).isEqualTo(9L)
    }

    @Test
    fun `setting the same shuffle state changes nothing`() {
        val change = playing(3).setShuffle(false)

        assertThat(change.edits).isEmpty()
        assertThat(change.queue).isEqualTo(playing(3))
    }

    @Test
    fun `shuffle on an empty queue only flips the flag`() {
        val change = PlayQueue().setShuffle(true)

        assertThat(change.queue.shuffled).isTrue()
        assertThat(change.edits).isEmpty()
    }

    @Test
    fun `move reorders upcoming tracks`() {
        val change = playing(5, current = 1).move(from = 4, to = 2)

        assertThat(change.queue.trackIds).containsExactly(1L, 2L, 5L, 3L, 4L).inOrder()
        assertThat(change.queue.currentIndex).isEqualTo(1)
        assertThat(change.edits).containsExactly(Move(4, 2))
        assertThat(change.queue.unshuffledTrackIds).isEqualTo(change.queue.trackIds)
    }

    @Test
    fun `move ignores the current track, played tracks and out of range indices`() {
        val queue = playing(4, current = 1)

        listOf(1 to 3, 3 to 1, 0 to 2, 2 to 0, 2 to 4, -1 to 2, 2 to 2).forEach { (from, to) ->
            val change = queue.move(from, to)
            assertThat(change.edits).isEmpty()
            assertThat(change.queue).isEqualTo(queue)
        }
    }

    @Test
    fun `moving while shuffled keeps the order shuffle off restores`() {
        val shuffled = playing(4).setShuffle(true, Random(2)).queue
        val moved = shuffled.move(from = 3, to = 1).queue

        assertThat(moved.setShuffle(false).queue.trackIds).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `remove drops an upcoming or played track but never the current one`() {
        val queue = playing(4, current = 1)

        val upcoming = queue.remove(3)
        assertThat(upcoming.queue.trackIds).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(upcoming.edits).containsExactly(Remove(3, 4))

        val played = queue.remove(0)
        assertThat(played.queue.trackIds).containsExactly(2L, 3L, 4L).inOrder()
        assertThat(played.queue.currentIndex).isEqualTo(0)

        listOf(1, 4, -1).forEach { assertThat(queue.remove(it).edits).isEmpty() }
    }

    @Test
    fun `remove while shuffled also drops the track from the restored order`() {
        val shuffled = playing(4).setShuffle(true, Random(9)).queue
        val removed = shuffled.remove(shuffled.trackIds.indexOf(3L)).queue

        assertThat(removed.setShuffle(false).queue.trackIds).containsExactly(1L, 2L, 4L).inOrder()
    }

    @Test
    fun `clear upcoming keeps played tracks and the current one`() {
        val change = playing(5, current = 2).clearUpcoming()

        assertThat(change.queue.trackIds).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(change.queue.currentIndex).isEqualTo(2)
        assertThat(change.edits).containsExactly(Remove(3, 5))
        assertThat(playing(3, current = 2).clearUpcoming().edits).isEmpty()
        assertThat(PlayQueue().clearUpcoming().edits).isEmpty()
    }

    @Test
    fun `clear upcoming while shuffled leaves only the remaining tracks to restore`() {
        val shuffled = playing(5, current = 2).setShuffle(true, Random(4)).queue
        val cleared = shuffled.clearUpcoming().queue

        assertThat(cleared.trackIds).containsExactly(3L)
        assertThat(cleared.setShuffle(false).queue.trackIds).containsExactly(3L)
    }

    @Test
    fun `skip to jumps to an index in range only`() {
        val queue = playing(3)

        assertThat(queue.skipTo(2).queue.currentIndex).isEqualTo(2)
        assertThat(queue.skipTo(2).edits).containsExactly(SkipTo(2))
        assertThat(queue.skipTo(3).edits).isEmpty()
        assertThat(queue.skipTo(-1).edits).isEmpty()
    }

    @Test
    fun `duplicate tracks get distinct stable item ids`() {
        val queue = playing(2).addToQueue(tracks(1)).queue.playNext(tracks(1)).queue

        assertThat(queue.trackIds).containsExactly(1L, 1L, 2L, 1L).inOrder()
        assertThat(queue.items.map { it.id }.toSet()).hasSize(4)

        val firstId = queue.items[0].id
        val removed = queue.remove(1).queue
        assertThat(removed.trackIds).containsExactly(1L, 2L, 1L).inOrder()
        assertThat(removed.items[0].id).isEqualTo(firstId)
        assertThat(removed.items.map { it.id }).doesNotContain(queue.items[1].id)
    }

    @Test
    fun `synced with the same items only takes the player position`() {
        val queue = playing(3)

        val synced = queue.synced(queue.items.map { it.copy(track = track(it.track.id).copy(title = "stale")) }, 2)

        assertThat(synced.currentIndex).isEqualTo(2)
        assertThat(synced.items).isEqualTo(queue.items)
    }

    @Test
    fun `synced with foreign items rebuilds the queue and never reuses their ids`() {
        val foreign = listOf(QueueItem(40, track(7)), QueueItem(41, track(8)))

        val synced = playing(3).setShuffle(true, Random(1)).queue.synced(foreign, 1)

        assertThat(synced.trackIds).containsExactly(7L, 8L).inOrder()
        assertThat(synced.currentIndex).isEqualTo(1)
        assertThat(synced.unshuffledIds.toSet()).containsExactly(40L, 41L)
        assertThat(synced.addToQueue(tracks(9)).queue.items.last().id).isGreaterThan(41L)
    }

    @Test
    fun `synced with an empty player empties the queue but keeps the shuffle intent`() {
        val synced = playing(3).setShuffle(true, Random(1)).queue.synced(emptyList(), 0)

        assertThat(synced.items).isEmpty()
        assertThat(synced.currentIndex).isEqualTo(-1)
        assertThat(synced.shuffled).isTrue()
    }

    @Test
    fun `shuffle turned on with an empty queue survives syncing with the empty player`() {
        val change = PlayQueue(nextId = 5).setShuffle(true)

        val synced = change.queue.synced(emptyList(), -1)

        assertThat(change.edits).isEmpty()
        assertThat(synced.shuffled).isTrue()
        assertThat(synced.nextId).isEqualTo(5)
    }

    @Test
    fun `tracks queued into an empty shuffled queue play shuffled and keep their order to restore`() {
        val empty = PlayQueue().setShuffle(true).queue

        for (queued in listOf(
            empty.addToQueue(tracks(1, 2, 3, 4, 5)).queue,
            empty.playNext(tracks(1, 2, 3, 4, 5)).queue,
            empty.playNow(tracks(1, 2, 3, 4, 5)).queue,
        )) {
            assertThat(queued.shuffled).isTrue()
            assertThat(queued.currentIndex).isEqualTo(0)
            assertThat(queued.unshuffledTrackIds).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
            assertThat(queued.setShuffle(false).queue.trackIds).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
        }
    }

    @Test
    fun `play from the library still sets shuffle as asked`() {
        val shuffledEmpty = PlayQueue().setShuffle(true).queue

        assertThat(shuffledEmpty.play(tracks(1, 2)).queue.shuffled).isFalse()
        assertThat(PlayQueue().play(tracks(1, 2), shuffle = true).queue.shuffled).isTrue()
    }

    @Test
    fun `updating a track swaps every entry for its file in place, the current one included`() {
        val queue = playing(3, current = 1).addToQueue(tracks(2)).queue
        val retagged = track(2).copy(title = "New")

        val change = queue.updateTrack(retagged)

        assertThat(change.queue.items.map { it.track.title }).containsExactly("T1", "New", "T3", "New").inOrder()
        assertThat(change.queue.items.map { it.id }).isEqualTo(queue.items.map { it.id })
        assertThat(change.queue.currentIndex).isEqualTo(1)
        assertThat(change.queue.unshuffledIds).isEqualTo(queue.unshuffledIds)
        assertThat(change.edits).containsExactly(
            Update(1, change.queue.items[1]),
            Update(3, change.queue.items[3]),
        ).inOrder()
    }

    @Test
    fun `updating a track that is not queued changes nothing`() {
        val queue = playing(2)

        assertThat(queue.updateTrack(track(9)).edits).isEmpty()
        assertThat(queue.updateTrack(track(9)).queue).isEqualTo(queue)
    }
}
