package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlaybackSnapshot
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotRecorderTest {

    /**
     * Records what was saved; [failNext] makes the next save throw like a full disk, and each of [positionDelays]
     * makes the next position save take that long (a busy disk).
     */
    private class FakeStore : PlaybackSnapshotStore {
        val saved = mutableListOf<PlaybackSnapshot>()
        val positions = mutableListOf<Pair<Long, Long>>()
        var failNext = false
        val positionDelays = ArrayDeque<Long>()

        override suspend fun load(): PlaybackSnapshot? = saved.lastOrNull()

        override suspend fun save(snapshot: PlaybackSnapshot) {
            if (failNext) { failNext = false; throw IOException("disk full") }
            saved += snapshot
        }

        override suspend fun savePosition(itemId: Long, positionMs: Long) {
            positionDelays.removeFirstOrNull()?.let { delay(it) }
            if (failNext) { failNext = false; throw IOException("disk full") }
            positions += itemId to positionMs
        }
    }

    private val store = FakeStore()

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", title = "T$id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    private fun snapshot(size: Int, current: Int = 0, positionMs: Long = 0, repeat: RepeatMode = RepeatMode.OFF): PlaybackSnapshot {
        val items = (1L..size).map { QueueItem(it, track(it)) }
        return PlaybackSnapshot(items, current, positionMs, false, items.map { it.id }, repeat)
    }

    private fun TestScope.recorder() = SnapshotRecorder(this, store, now = { testScheduler.currentTime })

    @Test
    fun `queue edits in quick succession are saved once, after they settle, with the latest queue`() = runTest {
        val recorder = recorder()
        recorder.onState(snapshot(3), isPlaying = false)
        advanceTimeBy(500)
        recorder.onState(snapshot(4), isPlaying = false)
        advanceTimeBy(500)
        recorder.onState(snapshot(5, repeat = RepeatMode.ALL), isPlaying = false)
        advanceTimeBy(999)
        runCurrent()
        assertThat(store.saved).isEmpty()

        advanceTimeBy(2)

        assertThat(store.saved).containsExactly(snapshot(5, repeat = RepeatMode.ALL))
    }

    @Test
    fun `moving to another entry saves the position right away without rewriting the queue`() = runTest {
        val recorder = recorder()
        recorder.onState(snapshot(5), isPlaying = true)
        advanceTimeBy(5_000)
        store.positions.clear()
        val savedQueues = store.saved.size

        recorder.onState(snapshot(5, current = 3, positionMs = 20), isPlaying = true)
        runCurrent()

        assertThat(store.positions).containsExactly(4L to 20L)
        assertThat(store.saved).hasSize(savedQueues)
    }

    @Test
    fun `pausing saves the position at once and flushes a pending queue save`() = runTest {
        val recorder = recorder()
        recorder.onState(snapshot(5, current = 1, positionMs = 1_000), isPlaying = true)
        runCurrent()
        store.positions.clear()

        recorder.onState(snapshot(6, current = 1, positionMs = 7_500), isPlaying = false)
        runCurrent()

        assertThat(store.positions).containsExactly(2L to 7_500L)
        assertThat(store.saved).containsExactly(snapshot(6, current = 1, positionMs = 7_500))
        advanceTimeBy(5_000)
        assertThat(store.saved).hasSize(1)
    }

    @Test
    fun `while playing the position is saved every ten seconds, and not while paused`() = runTest {
        val recorder = recorder()
        recorder.onState(snapshot(2), isPlaying = true)
        runCurrent()
        store.positions.clear()

        // The controller ticks every 250 ms while playing.
        for (tick in 1..100) {
            advanceTimeBy(250)
            recorder.onState(snapshot(2, positionMs = tick * 250L), isPlaying = true)
        }
        runCurrent()
        assertThat(store.positions).containsExactly(1L to 10_000L, 1L to 20_000L).inOrder()

        recorder.onState(snapshot(2, positionMs = 25_000), isPlaying = false)
        runCurrent()
        assertThat(store.positions.last()).isEqualTo(1L to 25_000L)
        store.positions.clear()
        for (tick in 1..80) {
            advanceTimeBy(250)
            recorder.onState(snapshot(2, positionMs = 25_000), isPlaying = false)
        }
        runCurrent()
        assertThat(store.positions).isEmpty()
    }

    @Test
    fun `an empty queue never overwrites the saved one`() = runTest {
        val recorder = recorder()
        recorder.onState(null, isPlaying = false)
        advanceTimeBy(5_000)

        assertThat(store.saved).isEmpty()
        assertThat(store.positions).isEmpty()
    }

    @Test
    fun `a failed save is survived and later saves go through`() = runTest {
        val recorder = recorder()
        store.failNext = true
        recorder.onState(snapshot(2), isPlaying = false)
        advanceTimeBy(2_000)

        recorder.onState(snapshot(3), isPlaying = false)
        advanceTimeBy(2_000)

        assertThat(store.saved.last()).isEqualTo(snapshot(3))
    }

    @Test
    fun `a slow save is never overtaken by a later one, so the newest position stays saved`() = runTest {
        val recorder = recorder()
        store.positionDelays += 100L
        recorder.onState(snapshot(5, current = 1, positionMs = 1_000), isPlaying = true)
        runCurrent()

        recorder.onState(snapshot(5, current = 1, positionMs = 2_000), isPlaying = false)
        advanceUntilIdle()

        assertThat(store.positions).containsExactly(2L to 1_000L, 2L to 2_000L).inOrder()
    }

    @Test
    fun `the end of the queue is saved as its last entry from the start`() = runTest {
        val recorder = recorder()
        recorder.onState(snapshot(3, current = 2, positionMs = 900), isPlaying = true)
        advanceTimeBy(2_000)
        store.positions.clear()

        recorder.onState(snapshot(3, current = 2, positionMs = 1_000), isPlaying = false, ended = true)
        runCurrent()
        assertThat(store.positions).containsExactly(3L to 0L)

        // A queue edit while ended (more songs added) keeps the last entry at its start too.
        recorder.onState(snapshot(4, current = 2, positionMs = 1_000), isPlaying = false, ended = true)
        advanceTimeBy(2_000)
        assertThat(store.saved.last()).isEqualTo(snapshot(4, current = 2, positionMs = 0))
        assertThat(store.positions).containsExactly(3L to 0L)
    }

    /** A queue list that counts how often it is compared element by element. */
    private class CountingList(private val items: List<QueueItem>) : List<QueueItem> by items {
        var comparisons = 0
        override fun equals(other: Any?): Boolean {
            comparisons++
            return items == other
        }
        override fun hashCode() = items.hashCode()
    }

    @Test
    fun `a position tick on an unchanged queue never compares the queue entry by entry`() = runTest {
        val recorder = recorder()
        val items = CountingList((1L..3L).map { QueueItem(it, track(it)) })
        val unshuffled = items.map { it.id }
        for (tick in 0..40) {
            recorder.onState(PlaybackSnapshot(items, 0, tick * 250L, false, unshuffled, RepeatMode.OFF), isPlaying = true)
        }
        runCurrent()

        assertThat(items.comparisons).isEqualTo(0)
    }
}
