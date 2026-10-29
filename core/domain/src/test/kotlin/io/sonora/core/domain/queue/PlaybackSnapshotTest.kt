package io.sonora.core.domain.queue

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import kotlin.random.Random
import org.junit.Test

class PlaybackSnapshotTest {

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", title = "T$id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    /** Items 10, 20, 30, 40, 50 holding tracks 1..5, playing the third at 42 s. */
    private val snapshot = PlaybackSnapshot(
        items = (1L..5L).map { QueueItem(it * 10, track(it)) },
        currentIndex = 2,
        positionMs = 42_000,
        shuffled = true,
        unshuffledIds = listOf(50L, 40L, 30L, 20L, 10L),
        repeatMode = RepeatMode.ALL,
    )

    private val PlaybackSnapshot.itemIds get() = items.map { it.id }

    @Test
    fun `a queue snapshot keeps play order, restore order, position and modes`() {
        val queue = PlayQueue().play((1L..4L).map(::track), startIndex = 1).queue.setShuffle(true, Random(3)).queue

        val taken = queue.snapshot(positionMs = 7_000, repeatMode = RepeatMode.ONE)!!

        assertThat(taken.items).isEqualTo(queue.items)
        assertThat(taken.currentIndex).isEqualTo(queue.currentIndex)
        assertThat(taken.unshuffledIds).isEqualTo(queue.unshuffledIds)
        assertThat(taken.shuffled).isTrue()
        assertThat(taken.positionMs).isEqualTo(7_000)
        assertThat(taken.repeatMode).isEqualTo(RepeatMode.ONE)
        assertThat(PlayQueue().snapshot(0, RepeatMode.OFF)).isNull()
    }

    @Test
    fun `the restored queue continues with fresh ids after the saved ones`() {
        val queue = snapshot.toQueue()

        assertThat(queue.items).isEqualTo(snapshot.items)
        assertThat(queue.currentIndex).isEqualTo(2)
        assertThat(queue.shuffled).isTrue()
        assertThat(queue.unshuffledIds).isEqualTo(snapshot.unshuffledIds)
        assertThat(queue.nextId).isEqualTo(51L)
        // Turning shuffle off after a restore brings back the saved restore order.
        assertThat(queue.setShuffle(false).queue.items.map { it.id }).isEqualTo(listOf(50L, 40L, 30L, 20L, 10L))
    }

    @Test
    fun `a fully playable snapshot is restored as saved`() {
        assertThat(snapshot.restorable { true }).isEqualTo(snapshot)
    }

    @Test
    fun `unplayable entries are dropped from both orders and the current one keeps its position`() {
        val restored = snapshot.restorable { it.id != 20L && it.id != 50L }!!

        assertThat(restored.itemIds).containsExactly(10L, 30L, 40L).inOrder()
        assertThat(restored.unshuffledIds).containsExactly(40L, 30L, 10L).inOrder()
        assertThat(restored.items[restored.currentIndex].id).isEqualTo(30L)
        assertThat(restored.positionMs).isEqualTo(42_000)
    }

    @Test
    fun `an unplayable current entry moves to the next playable one from its start`() {
        val restored = snapshot.restorable { it.id != 30L && it.id != 40L }!!

        assertThat(restored.items[restored.currentIndex].id).isEqualTo(50L)
        assertThat(restored.positionMs).isEqualTo(0L)
    }

    @Test
    fun `without a playable entry after the current one the last playable one before it is used`() {
        val restored = snapshot.restorable { it.id <= 20L }!!

        assertThat(restored.items[restored.currentIndex].id).isEqualTo(20L)
        assertThat(restored.positionMs).isEqualTo(0L)
    }

    @Test
    fun `nothing playable or a broken snapshot restores nothing`() {
        assertThat(snapshot.restorable { false }).isNull()
        assertThat(snapshot.copy(currentIndex = 9).restorable { true }?.currentIndex).isEqualTo(4)
        assertThat(snapshot.copy(positionMs = -5).restorable { true }?.positionMs).isEqualTo(0L)
        // Restore-order ids that are not queued, or queued ids it misses, are repaired rather than trusted.
        val repaired = snapshot.copy(unshuffledIds = listOf(99L, 30L)).restorable { true }!!
        assertThat(repaired.unshuffledIds).containsExactly(30L, 10L, 20L, 40L, 50L).inOrder()
    }

    @Test
    fun `an unshuffled snapshot restores in play order`() {
        val restored = snapshot.copy(shuffled = false).restorable { it.id != 10L }!!

        assertThat(restored.unshuffledIds).isEqualTo(restored.itemIds)
    }
}
