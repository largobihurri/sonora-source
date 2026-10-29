package io.sonora.core.domain.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.batch.FakeConverter
import io.sonora.core.domain.conversion.batch.FakeRepository
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.conversion.batch.ResolveConvertedTracks
import io.sonora.core.domain.conversion.batch.newBatch
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.ConvertedFile
import io.sonora.core.domain.port.ConvertedFileReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class ObserveConvertedMusicTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = FakeRepository()

    /** Output URIs that exist; every other output is gone. */
    private val existing = mutableSetOf<String>()
    private val reads = mutableListOf<String>()
    private val reader = ConvertedFileReader { uri, name ->
        reads += uri
        if (uri in existing) ConvertedFile(TrackTags(title = name.substringBeforeLast('.')), null, "audio/aiff", 1) else null
    }
    private val observe = ObserveConvertedMusic(repository, ResolveConvertedTracks(repository, reader), clock)

    private fun daysAgo(days: Long, hours: Long = 0) = now.minusSeconds(days * 86_400 + hours * 3_600).toEpochMilli()

    /** A finished batch created at [createdAtMs] whose [converted] files were converted and [failed] were not. */
    private suspend fun batch(createdAtMs: Long, converted: List<String>, failed: List<String> = emptyList()): Long {
        val names = converted + failed
        val id = repository.create(newBatch(createdAtMs), names.map { BatchSource("content://src/$it", "$it.flac", "content://src") })
        names.forEachIndexed { position, name ->
            val item = repository.item(id, position)
            repository.updateItem(
                if (name in converted) {
                    item.copy(state = BatchItemState.DONE, outputUri = "content://out/$name", outputName = "$name.aiff", result = FakeConverter.result())
                } else {
                    item.copy(state = BatchItemState.FAILED, failureReason = "damaged")
                },
            )
            if (name in converted) existing += "content://out/$name"
        }
        repository.transition(id, setOf(BatchStatus.QUEUED), BatchStatus.COMPLETED)
        return id
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) yield()
    }

    private fun List<ConvertedMusicSection>.titles() = map { section -> section.section to section.tracks.map { it.title } }

    @Test
    fun `converted songs of every batch, by date section, newest batch first, each batch in its own order`() = runBlocking<Unit> {
        batch(daysAgo(40), listOf("Old"))
        batch(daysAgo(0, hours = 3), listOf("Morning 1", "Morning 2"), failed = listOf("Broken"))
        batch(daysAgo(0, hours = 1), listOf("Noon"))
        batch(daysAgo(1), listOf("Yesterday"))
        batch(daysAgo(3), emptyList(), failed = listOf("Nothing"))

        val sections = observe().first()

        assertThat(sections.titles()).containsExactly(
            HistorySection.TODAY to listOf("Noon", "Morning 1", "Morning 2"),
            HistorySection.YESTERDAY to listOf("Yesterday"),
            HistorySection.OLDER to listOf("Old"),
        ).inOrder()
    }

    @Test
    fun `outputs that no longer exist are left out, and a section left empty disappears`() = runBlocking<Unit> {
        batch(daysAgo(0), listOf("Kept", "Deleted"))
        batch(daysAgo(10), listOf("Moved"))
        existing -= setOf("content://out/Deleted", "content://out/Moved")

        assertThat(observe().first().titles()).containsExactly(HistorySection.TODAY to listOf("Kept"))
    }

    @Test
    fun `each section keeps its batches with their creation time, empty batches left out`() = runBlocking<Unit> {
        val morning = batch(daysAgo(0, hours = 3), listOf("Morning"))
        batch(daysAgo(0, hours = 2), emptyList(), failed = listOf("Broken"))
        val noon = batch(daysAgo(0, hours = 1), listOf("Noon 1", "Noon 2"))

        val today = observe().first().single()

        assertThat(today.batches.map { Triple(it.batchId, it.createdAtMs, it.tracks.map(Track::title)) }).containsExactly(
            Triple(noon, daysAgo(0, hours = 1), listOf("Noon 1", "Noon 2")),
            Triple(morning, daysAgo(0, hours = 3), listOf("Morning")),
        ).inOrder()
    }

    @Test
    fun `no history means no converted music`() = runBlocking<Unit> {
        assertThat(observe().first()).isEmpty()
    }

    @Test
    fun `newly converted songs appear, other history changes do not re-read the files`() = runBlocking<Unit> {
        val first = batch(daysAgo(0, hours = 2), listOf("A"))
        val emitted = mutableListOf<List<ConvertedMusicSection>>()
        val collecting = launch { observe().toList(emitted) }
        awaitUntil { emitted.isNotEmpty() }
        assertThat(emitted.last().titles()).containsExactly(HistorySection.TODAY to listOf("A"))
        val readsBefore = reads.size

        // Same files, same outcome: nothing to re-read.
        repository.updateBatch(repository.getBatch(first)!!.copy(failureReason = "note"))
        repeat(20) { yield() }
        assertThat(reads).hasSize(readsBefore)
        assertThat(emitted).hasSize(1)

        batch(daysAgo(0, hours = 1), listOf("B"))
        awaitUntil { emitted.last().titles() == listOf(HistorySection.TODAY to listOf("B", "A")) }
        collecting.cancel()
    }

    /** A clock that stays where it is put. */
    private class SettableClock(var instant: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = SettableClock(instant, zone)
        override fun instant(): Instant = instant
    }

    @Test
    fun `the date sections move on when the day changes, without re-reading the files`() = runBlocking<Unit> {
        val clock = SettableClock(now)
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val observe = ObserveConvertedMusic(repository, ResolveConvertedTracks(repository, reader), clock, ticks)
        batch(daysAgo(0, hours = 1), listOf("Late"))
        val emitted = mutableListOf<List<ConvertedMusicSection>>()
        val collecting = launch { observe().toList(emitted) }
        awaitUntil { emitted.isNotEmpty() && ticks.subscriptionCount.value > 0 }
        assertThat(emitted.last().titles()).containsExactly(HistorySection.TODAY to listOf("Late"))
        val readsBefore = reads.size

        // A check on the same day changes nothing.
        ticks.emit(Unit)
        repeat(20) { yield() }
        assertThat(emitted).hasSize(1)

        clock.instant = Instant.parse("2026-10-04T00:00:01Z")
        ticks.emit(Unit)
        awaitUntil { emitted.last().titles() == listOf(HistorySection.YESTERDAY to listOf("Late")) }
        assertThat(reads).hasSize(readsBefore)
        collecting.cancel()
    }

    @Test
    fun `the day is checked again at the next midnight in the clock's zone, and at least every minute`() {
        fun wait(at: String, zone: ZoneId = ZoneOffset.UTC) = ObserveConvertedMusic.untilNextDayCheckMs(Clock.fixed(Instant.parse(at), zone))

        assertThat(wait("2026-10-03T23:59:30Z")).isEqualTo(30_000)
        assertThat(wait("2026-10-03T23:59:59.999Z")).isEqualTo(1)
        assertThat(wait("2026-10-03T12:00:00Z")).isEqualTo(60_000)
        // 23:59:50 in Madrid (UTC+2 in October).
        assertThat(wait("2026-10-03T21:59:50Z", ZoneId.of("Europe/Madrid"))).isEqualTo(10_000)
    }
}
