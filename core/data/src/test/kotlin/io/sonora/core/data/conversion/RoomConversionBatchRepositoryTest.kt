package io.sonora.core.data.conversion

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.conversion.db.ConversionBatchItemEntity
import io.sonora.core.data.conversion.db.ResultColumns
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchCounts
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomConversionBatchRepositoryTest {

    private lateinit var db: LibraryDatabase
    private lateinit var repository: RoomConversionBatchRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomConversionBatchRepository(db.conversionHistoryDao())
    }

    @After
    fun tearDown() = db.close()

    private fun batch(createdAtMs: Long, overwrite: Boolean = false) = ConversionBatch(
        id = 99, // ignored on create
        profileId = ConversionProfiles.XDJ_700.id,
        performanceMode = PerformanceMode.LOW_RESOURCES,
        createdAtMs = createdAtMs,
        outputFolderName = "2026-10-03 14.05",
        outputFolderUri = null,
        overwriteOriginals = overwrite,
        status = BatchStatus.QUEUED,
    )

    private val sources = listOf(
        BatchSource("content://tree/music/document/a.flac", "a.flac", "content://tree/music/document/music"),
        BatchSource("content://media/document/b.mp3", "b.mp3", null),
        BatchSource("content://tree/music/document/c.wav", "c.wav", "content://tree/music/document/music"),
    )

    private val result = ConversionResult(
        plan = ConversionPlan(ConversionProfiles.XDJ_700, 96_000, 44_100, 2, 2, 16, dither = true),
        outputFrames = 441_000,
        sourceFrames = 960_000,
        tagsWritten = true,
    )

    @Test
    fun `a created batch reads back with its items in order and all pending`() = runTest {
        val id = repository.create(batch(createdAtMs = 10, overwrite = true), sources)

        val stored = repository.getBatch(id)!!
        assertThat(stored).isEqualTo(
            batch(10, overwrite = true).copy(id = id, counts = BatchCounts(total = 3, pending = 3), profileIds = setOf("xdj-700")),
        )
        val items = repository.getItems(id)
        assertThat(items.map { it.position }).containsExactly(0, 1, 2).inOrder()
        assertThat(items.map { it.sourceName }).containsExactly("a.flac", "b.mp3", "c.wav").inOrder()
        assertThat(items[1].sourceParentUri).isNull()
        assertThat(items.map { it.state }.toSet()).containsExactly(BatchItemState.PENDING)
        assertThat(items.map { it.result }.toSet()).containsExactly(null)
        assertThat(repository.items(id).first()).isEqualTo(items)
    }

    @Test
    fun `each item keeps its own profile and the batch lists every profile of its items`() = runTest {
        val mixed = listOf(sources[0], sources[1].copy(profileId = ConversionProfiles.WATCH_OPUS.id), sources[2])
        val id = repository.create(batch(1), mixed)
        val watchResult = result.copy(plan = result.plan.copy(profile = ConversionProfiles.WATCH_OPUS, targetSampleRateHz = 48_000))
        val b = repository.getItems(id)[1]
        repository.updateItem(b.copy(state = BatchItemState.DONE, outputUri = "out:b", tagsWritten = true, result = watchResult))

        val items = repository.getItems(id)
        assertThat(items.map { it.profileId }).containsExactly("xdj-700", "watch-opus", "xdj-700").inOrder()
        // The result is rebuilt with the item's own profile, not the batch's.
        assertThat(items[1].result).isEqualTo(watchResult)
        assertThat(repository.items(id).first()).isEqualTo(items)
        val stored = repository.getBatch(id)!!
        assertThat(stored.profileId).isEqualTo("xdj-700")
        assertThat(stored.profileIds).containsExactly("xdj-700", "watch-opus")
        assertThat(stored.hasMixedProfiles).isTrue()
        assertThat(repository.batches().first().single().profileIds).containsExactly("xdj-700", "watch-opus")
        assertThat(repository.batch(id).first()!!.hasMixedProfiles).isTrue()
    }

    @Test
    fun `a batch whose items share one profile is not mixed, and one without items lists none`() = runTest {
        val single = repository.create(batch(1).copy(profileId = ConversionProfiles.WATCH_OPUS.id), sources)
        val empty = repository.create(batch(2), emptyList())

        assertThat(repository.getItems(single).map { it.profileId }.toSet()).containsExactly("watch-opus")
        assertThat(repository.getBatch(single)!!.profileIds).containsExactly("watch-opus")
        assertThat(repository.getBatch(single)!!.hasMixedProfiles).isFalse()
        assertThat(repository.getBatch(empty)!!.profileIds).isEmpty()
    }

    @Test
    fun `item updates keep the conversion result and feed the counts`() = runTest {
        val id = repository.create(batch(1), sources)
        val (a, b, c) = repository.getItems(id)

        repository.updateItem(a.copy(state = BatchItemState.DONE, outputUri = "out:a", outputName = "a.aiff", tagsWritten = true, result = result))
        repository.updateItem(b.copy(state = BatchItemState.REPLACED_ORIGINAL, outputUri = "out:b", suffixedName = true, result = result.copy(tagsWritten = false)))
        repository.updateItem(c.copy(state = BatchItemState.FAILED, failureReason = "Cannot decode the source"))

        val items = repository.getItems(id)
        assertThat(items[0].result).isEqualTo(result)
        assertThat(items[0].outputName).isEqualTo("a.aiff")
        assertThat(items[1].suffixedName).isTrue()
        assertThat(items[1].tagsWritten).isFalse()
        assertThat(items[2].failureReason).isEqualTo("Cannot decode the source")
        assertThat(repository.batch(id).first()!!.counts)
            .isEqualTo(BatchCounts(total = 3, done = 1, failed = 1, replacedOriginals = 1, withoutTags = 1))
    }

    @Test
    fun `batch updates write status, folder and failure`() = runTest {
        val id = repository.create(batch(1), sources)

        repository.updateBatch(
            repository.getBatch(id)!!.copy(status = BatchStatus.FAILED, outputFolderUri = "folder", failureReason = "gone"),
        )

        val stored = repository.getBatch(id)!!
        assertThat(stored.status).isEqualTo(BatchStatus.FAILED)
        assertThat(stored.outputFolderUri).isEqualTo("folder")
        assertThat(stored.failureReason).isEqualTo("gone")
    }

    @Test
    fun `batches list newest first, and deleting one removes its items only`() = runTest {
        val older = repository.create(batch(1), sources)
        val newer = repository.create(batch(2), sources.take(1))

        assertThat(repository.batches().first().map { it.id }).containsExactly(newer, older).inOrder()
        assertThat(repository.batches().first().map { it.counts.total }).containsExactly(1, 3).inOrder()

        repository.delete(older)

        assertThat(repository.batches().first().map { it.id }).containsExactly(newer)
        assertThat(repository.getItems(older)).isEmpty()
        assertThat(repository.getItems(newer)).hasSize(1)
        assertThat(repository.getBatch(older)).isNull()
    }

    @Test
    fun `an empty batch has zero counts`() = runTest {
        val id = repository.create(batch(1), emptyList())

        assertThat(repository.getBatch(id)!!.counts).isEqualTo(BatchCounts())
    }

    @Test
    fun `a status transition happens only from the expected statuses`() = runTest {
        val id = repository.create(batch(1), sources)
        val active = setOf(BatchStatus.QUEUED, BatchStatus.RUNNING)

        assertThat(repository.transition(id, active, BatchStatus.RUNNING)).isTrue()
        assertThat(repository.transition(id, setOf(BatchStatus.QUEUED), BatchStatus.CANCELLED)).isFalse()
        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.RUNNING)

        assertThat(repository.transition(id, active, BatchStatus.FAILED, "boom")).isTrue()
        assertThat(repository.transition(id, active, BatchStatus.CANCELLED)).isFalse()
        val ended = repository.getBatch(id)!!
        assertThat(ended.status).isEqualTo(BatchStatus.FAILED)
        assertThat(ended.failureReason).isEqualTo("boom")
        assertThat(repository.transition(404, active, BatchStatus.CANCELLED)).isFalse()
    }

    @Test
    fun `a converted file whose original could not be replaced counts as converted and as failed`() = runTest {
        val id = repository.create(batch(1), sources)
        val (a, b) = repository.getItems(id)

        repository.updateItem(
            a.copy(state = BatchItemState.FAILED, outputUri = "out:a", tagsWritten = false, failureReason = "No write access", result = result),
        )
        repository.updateItem(b.copy(state = BatchItemState.FAILED, failureReason = "Cannot decode the source"))

        val counts = repository.getBatch(id)!!.counts
        assertThat(counts.failed).isEqualTo(2)
        assertThat(counts.notReplaced).isEqualTo(1)
        assertThat(counts.converted).isEqualTo(1)
        assertThat(counts.withoutTags).isEqualTo(1)
    }

    private fun itemRow(state: String, result: ResultColumns?) = ConversionBatchItemEntity(
        id = 1, batchId = 1, position = 0, sourceUri = "content://a.flac", sourceName = "a.flac", sourceParentUri = "content://music",
        state = state, outputUri = "content://out/a.aiff", outputName = "a.aiff", tagsWritten = true, failureReason = null,
        suffixedName = false, result = result,
    )

    @Test
    fun `an item state written by a newer version reads as failed and is never replaceable`() {
        val item = itemRow("MOVING_ORIGINAL", ResultColumns.of(result)).toDomain(ConversionProfiles.XDJ_700.id)

        assertThat(item.state).isEqualTo(BatchItemState.FAILED)
        assertThat(item.failureReason).contains("MOVING_ORIGINAL")
        // Its output may sit anywhere by now: "replace originals" must not move or delete anything for it.
        assertThat(item.canReplaceOriginal).isFalse()
        assertThat(itemRow("DONE", ResultColumns.of(result)).toDomain(ConversionProfiles.XDJ_700.id).canReplaceOriginal).isTrue()
    }

    @Test
    fun `stored results that cannot describe a conversion read as no result`() {
        val good = ResultColumns.of(result)
        val broken = listOf(
            good.copy(targetSampleRateHz = 0),
            good.copy(sourceSampleRateHz = -1),
            good.copy(targetChannels = 0),
            good.copy(sourceChannels = 0),
            good.copy(targetBitsPerSample = 0),
            good.copy(outputFrames = -1),
            good.copy(sourceFrames = -1),
        )

        assertThat(good.toDomain(ConversionProfiles.XDJ_700.id, tagsWritten = true)).isEqualTo(result)
        assertThat(broken.map { it.toDomain(ConversionProfiles.XDJ_700.id, tagsWritten = true) }.toSet()).containsExactly(null)
        assertThat(good.toDomain("gone-profile", tagsWritten = true)).isNull()
    }

    @Test
    fun `concurrent status transitions from the same statuses have exactly one winner`() = runBlocking<Unit> {
        // A real database on its own threads, as the job claiming a batch and the user cancelling it use it.
        val threaded = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java).build()
        try {
            val rooms = RoomConversionBatchRepository(threaded.conversionHistoryDao())
            val active = setOf(BatchStatus.QUEUED, BatchStatus.RUNNING)
            repeat(20) {
                val id = rooms.create(batch(1), sources)
                val endings = listOf(BatchStatus.CANCELLED, BatchStatus.FAILED, BatchStatus.TIMED_OUT, BatchStatus.COMPLETED)
                val won = endings.map { to -> async(Dispatchers.IO) { to to rooms.transition(id, active, to, "by $to") } }.awaitAll()

                val winners = won.filter { it.second }.map { it.first }
                assertThat(winners).hasSize(1)
                val stored = rooms.getBatch(id)!!
                assertThat(stored.status).isEqualTo(winners.single())
                assertThat(stored.failureReason).isEqualTo("by ${winners.single()}")
            }
        } finally {
            threaded.close()
        }
    }

    @Test
    fun `a status transition writes only the status and reason, and clears a reason not given`() = runTest {
        val id = repository.create(batch(1), sources)
        repository.updateBatch(repository.getBatch(id)!!.copy(outputFolderUri = "content://folder", failureReason = "stale"))

        assertThat(repository.transition(id, setOf(BatchStatus.QUEUED), BatchStatus.RUNNING)).isTrue()

        val running = repository.getBatch(id)!!
        assertThat(running.failureReason).isNull()
        assertThat(running.outputFolderUri).isEqualTo("content://folder")
        assertThat(running.counts.pending).isEqualTo(3)
        assertThat(repository.transition(id, emptySet(), BatchStatus.CANCELLED)).isFalse()
    }

    @Test
    fun `only a failed file whose verified output can still replace its original counts as not replaced`() = runTest {
        val id = repository.create(batch(1), sources)
        val (a, b) = repository.getItems(id)

        repository.updateItem(a.copy(state = BatchItemState.FAILED, outputUri = "out:a", tagsWritten = false, result = result))
        // An output on record without its conversion result cannot be verified, so it can never replace its original.
        repository.updateItem(b.copy(state = BatchItemState.FAILED, outputUri = "out:b", tagsWritten = false, result = null))

        val counts = repository.getBatch(id)!!.counts
        val items = repository.getItems(id)
        assertThat(counts.notReplaced).isEqualTo(items.count { it.state == BatchItemState.FAILED && it.canReplaceOriginal })
        assertThat(counts.notReplaced).isEqualTo(1)
        assertThat(counts.converted).isEqualTo(1)
        assertThat(counts.withoutTags).isEqualTo(1)
    }
}
