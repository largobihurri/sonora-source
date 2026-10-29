package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.port.UnsupportedOutputException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class ConversionBatchRunnerTest {

    private val files = FakeFiles()
    private val outputRoot = files.folder("USB")
    private val music = files.folder("Music")
    private val repository = FakeRepository()
    private val converter = FakeConverter(files)
    private val access = FakeAccess(writable = setOf(music))
    private var budgets = FakeBudgets()
    private val root = FakeOutputRoot(outputRoot)

    private fun runner(stallTimeout: Duration = ConversionBatchRunner.DEFAULT_STALL_TIMEOUT) = ConversionBatchRunner(
        repository, files, root, converter, budgets, OriginalReplacer(files, access, converter, repository), stallTimeout,
    )

    /** Waits (on real time, bounded) until [condition] holds. */
    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    /** Stores a batch over sources in Music, created from "name=content" pairs. */
    private suspend fun batchOf(vararg sources: Pair<String, String>, overwrite: Boolean = false): Long =
        repository.create(
            newBatch(overwrite = overwrite, mode = PerformanceMode.HIGH_PERFORMANCE),
            sources.map { (name, content) -> BatchSource(files.file(music, name, content), name, music) },
        )

    private fun converted() = files.uriOf(outputRoot, "Convertidos")!!

    private fun batchFolder(name: String = "2026-10-03 14.05") = files.uriOf(converted(), name)!!

    @Test
    fun `every file is converted into the batch's dated folder inside Convertidos`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.mp3" to "2")

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(batch.counts.done).isEqualTo(2)
        assertThat(batch.outputFolderUri).isEqualTo(batchFolder())
        assertThat(files.names(batchFolder())).containsExactly("One.aiff", "Two.aiff")
        val one = repository.item(id, 0)
        assertThat(one.state).isEqualTo(BatchItemState.DONE)
        assertThat(one.outputName).isEqualTo("One.aiff")
        assertThat(files.content(one.outputUri!!)).isEqualTo("AIFF:1")
        assertThat(one.result).isNotNull()
        assertThat(one.tagsWritten).isTrue()
        // Sources stay where they are without overwrite.
        assertThat(files.names(music)).containsExactly("One.flac", "Two.mp3")
        assertThat(budgets.requested.toSet()).containsExactly(PerformanceMode.HIGH_PERFORMANCE)
        assertThat(converter.requests.map { it.performanceMode }.toSet()).containsExactly(PerformanceMode.HIGH_PERFORMANCE)
    }

    @Test
    fun `each file converts with its own profile and takes that profile's extension`() = runBlocking<Unit> {
        val id = repository.create(
            newBatch(),
            listOf(
                BatchSource(files.file(music, "One.flac", "1"), "One.flac", music),
                BatchSource(files.file(music, "Two.flac", "2"), "Two.flac", music, profileId = ConversionProfiles.WATCH_OPUS.id),
            ),
        )

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(files.names(batchFolder())).containsExactly("One.aiff", "Two.opus")
        assertThat(converter.requests.associate { it.sourceUri to it.profile.id })
            .containsExactly(repository.item(id, 0).sourceUri, "xdj-700", repository.item(id, 1).sourceUri, "watch-opus")
        assertThat(repository.item(id, 1).profileId).isEqualTo("watch-opus")
        assertThat(repository.item(id, 1).outputName).isEqualTo("Two.opus")
        assertThat(batch.profileIds).containsExactly("xdj-700", "watch-opus")
        assertThat(batch.hasMixedProfiles).isTrue()
    }

    @Test
    fun `two files of different profiles never share an output name`() = runBlocking<Unit> {
        val id = repository.create(
            newBatch(),
            listOf(
                BatchSource(files.file(music, "One.flac", "1"), "One.flac", music, profileId = ConversionProfiles.WATCH_OPUS.id),
                BatchSource(files.file(music, "One.wav", "2"), "One.wav", music, profileId = ConversionProfiles.WATCH_OPUS.id),
                BatchSource(files.file(music, "One.mp3", "3"), "One.mp3", music),
            ),
        )

        runner().run(id)

        assertThat(files.names(batchFolder())).containsExactly("One.opus", "One (2).opus", "One.aiff")
    }

    @Test
    fun `a file whose profile is unknown fails alone`() = runBlocking<Unit> {
        val id = repository.create(
            newBatch(),
            listOf(
                BatchSource(files.file(music, "One.flac", "1"), "One.flac", music, profileId = "cdj-3000"),
                BatchSource(files.file(music, "Two.flac", "2"), "Two.flac", music),
            ),
        )

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        val unknown = repository.item(id, 0)
        assertThat(unknown.state).isEqualTo(BatchItemState.FAILED)
        assertThat(unknown.failureReason).isEqualTo("Unknown conversion profile cdj-3000")
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.DONE)
        assertThat(files.names(batchFolder())).containsExactly("Two.aiff")
    }

    @Test
    fun `a file whose format has no encoder yet fails alone with a clear reason`() = runBlocking<Unit> {
        val id = repository.create(
            newBatch(),
            listOf(
                BatchSource(files.file(music, "One.flac", "1"), "One.flac", music, profileId = ConversionProfiles.WATCH_OPUS.id),
                BatchSource(files.file(music, "Two.flac", "2"), "Two.flac", music),
            ),
        )
        converter.failures[repository.item(id, 0).sourceUri] = UnsupportedOutputException(ConversionProfiles.WATCH_OPUS)

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        val unsupported = repository.item(id, 0)
        assertThat(unsupported.state).isEqualTo(BatchItemState.FAILED)
        assertThat(unsupported.failureReason).isEqualTo("This version cannot write Wear OS watch (Opus) files yet")
        assertThat(unsupported.outputUri).isNull()
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.DONE)
        // The failed file's part file is gone.
        assertThat(files.names(batchFolder())).containsExactly("Two.aiff")
    }

    @Test
    fun `a file this device cannot write says why`() = runBlocking<Unit> {
        val id = repository.create(newBatch(), listOf(BatchSource(files.file(music, "One.flac", "1"), "One.flac", music)))
        converter.failures[repository.item(id, 0).sourceUri] =
            UnsupportedOutputException(ConversionProfiles.XDJ_700, reason = "the FLAC encoder takes no 24-bit input")

        runner().run(id)

        assertThat(repository.item(id, 0).failureReason)
            .isEqualTo("This device cannot write Pioneer XDJ-700 files: the FLAC encoder takes no 24-bit input")
    }

    @Test
    fun `replacing originals later names each copy after its own profile`() = runBlocking<Unit> {
        val id = repository.create(
            newBatch(),
            listOf(
                BatchSource(files.file(music, "One.flac", "1"), "One.flac", music),
                BatchSource(files.file(music, "Two.flac", "2"), "Two.flac", music, profileId = ConversionProfiles.WATCH_OPUS.id),
            ),
        )
        runner().run(id)

        val batch = ReplaceBatchOriginals(repository, OriginalReplacer(files, access, converter, repository))(id)!!

        assertThat(batch.counts.replacedOriginals).isEqualTo(2)
        assertThat(files.names(music)).containsExactly("One.aiff", "Two.opus")
    }

    @Test
    fun `replacing originals later leaves a file of an unknown profile as it is`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "2")
        runner().run(id)
        val two = repository.item(id, 1)
        repository.updateItem(two.copy(profileId = "cdj-3000"))

        val batch = ReplaceBatchOriginals(repository, OriginalReplacer(files, access, converter, repository))(id)!!

        assertThat(batch.counts.replacedOriginals).isEqualTo(1)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.DONE)
        assertThat(files.names(music)).containsExactly("One.aiff", "Two.flac")
    }

    @Test
    fun `outputs are written as part files and verified before taking their name`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1")

        runner().run(id)

        val written = converter.requests.single().outputUri
        assertThat(converter.verified).contains(written)
        // The verified part file was renamed into place: same document, new name and URI.
        assertThat(files.nodes).doesNotContainKey(written)
        assertThat(files.names(batchFolder())).containsExactly("One.aiff")
    }

    @Test
    fun `existing Convertidos folders and batch folders are reused or numbered`() = runBlocking<Unit> {
        val existing = files.folder("convertidos", outputRoot)
        files.folder("2026-10-03 14.05", existing)

        runner().run(batchOf("One.flac" to "1"))

        assertThat(files.names(outputRoot)).containsExactly("convertidos")
        assertThat(files.names(existing)).containsExactly("2026-10-03 14.05", "2026-10-03 14.05 (2)")
        assertThat(files.names(files.uriOf(existing, "2026-10-03 14.05 (2)")!!)).containsExactly("One.aiff")
    }

    @Test
    fun `sources sharing a base name get numbered outputs`() = runBlocking<Unit> {
        val id = batchOf("Song.flac" to "a", "Song.mp3" to "b", "song.wav" to "c")

        runner().run(id)

        assertThat(files.names(batchFolder())).containsExactly("Song.aiff", "Song (2).aiff", "song (3).aiff")
    }

    @Test
    fun `output names are made safe for FAT sticks`() = runBlocking<Unit> {
        val id = repository.create(newBatch(), listOf(BatchSource(files.file(music, "x", "1"), "AC:DC? Live.flac", music)))

        runner().run(id)

        assertThat(files.names(batchFolder())).containsExactly("AC_DC_ Live.aiff")
    }

    @Test
    fun `a source that does not decode fails alone, with its reason`() = runBlocking<Unit> {
        val id = batchOf("Bad.flac" to "x", "Good.flac" to "y")
        converter.failures[repository.item(id, 0).sourceUri] = PcmDecodeException.CorruptData("bad frame header")

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        val bad = repository.item(id, 0)
        assertThat(bad.state).isEqualTo(BatchItemState.FAILED)
        assertThat(bad.failureReason).contains("bad frame header")
        assertThat(bad.outputUri).isNull()
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.DONE)
        assertThat(files.names(batchFolder())).containsExactly("Good.aiff")
    }

    @Test
    fun `an output that does not verify fails and is deleted`() = runBlocking<Unit> {
        val id = batchOf("Broken.flac" to "corrupt")

        runner().run(id)

        val item = repository.item(id, 0)
        assertThat(item.state).isEqualTo(BatchItemState.FAILED)
        assertThat(item.failureReason).contains("bad data")
        assertThat(files.names(batchFolder())).isEmpty()
    }

    @Test
    fun `files converted without tags are recorded`() = runBlocking<Unit> {
        val id = batchOf("Tagged.flac" to "1", "Bare.wav" to "2")
        converter.untagged += repository.item(id, 1).sourceUri

        val batch = runner().run(id)!!

        assertThat(repository.item(id, 0).tagsWritten).isTrue()
        assertThat(repository.item(id, 1).tagsWritten).isFalse()
        assertThat(batch.counts.withoutTags).isEqualTo(1)
    }

    @Test
    fun `without rename support the verified part is copied to its final name`() = runBlocking<Unit> {
        files.renameSupported = false
        val id = batchOf("One.flac" to "1")

        runner().run(id)

        assertThat(files.names(batchFolder())).containsExactly("One.aiff")
        val item = repository.item(id, 0)
        assertThat(item.state).isEqualTo(BatchItemState.DONE)
        assertThat(files.content(item.outputUri!!)).isEqualTo("AIFF:1")
    }

    @Test
    fun `without an output folder the batch fails and its files are skipped`() = runBlocking<Unit> {
        root.root.value = null
        val id = batchOf("One.flac" to "1")

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.FAILED)
        assertThat(batch.failureReason).contains("output folder")
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.SKIPPED)
        assertThat(converter.requests).isEmpty()
    }

    @Test
    fun `an output folder that cannot be created fails the batch`() = runBlocking<Unit> {
        root.root.value = root.root.value!!.copy(treeUri = "doc:gone")
        val id = batchOf("One.flac" to "1")

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.FAILED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.SKIPPED)
    }

    @Test
    fun `cancelling deletes the part being written and keeps finished files`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "2", "Three.flac" to "3")
        val gate = CompletableDeferred<Unit>()
        converter.gates[repository.item(id, 1).sourceUri] = gate

        val job = launch { runner().run(id) }
        while (converter.running == 0 || converter.requests.size < 2) yield()
        job.cancelAndJoin()

        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.DONE)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.PENDING)
        assertThat(repository.item(id, 2).state).isEqualTo(BatchItemState.PENDING)
        assertThat(files.names(batchFolder())).containsExactly("One.aiff")

        val batch = runner().finishInterrupted(id, BatchStatus.CANCELLED)!!

        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.DONE)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.SKIPPED)
        assertThat(repository.item(id, 2).state).isEqualTo(BatchItemState.SKIPPED)
        assertThat(batch.counts.skipped).isEqualTo(2)
    }

    @Test
    fun `a batch interrupted by the system resumes where it stopped`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "2")
        runner().run(id)
        // As if the process died while converting Two: it is still running and its part file is left behind.
        val folder = batchFolder()
        val two = repository.item(id, 1)
        files.delete(two.outputUri!!)
        files.file(folder, "Two.aiff.part", "half")
        repository.updateItem(two.copy(state = BatchItemState.RUNNING, outputUri = null, outputName = null, result = null))
        repository.updateBatch(repository.getBatch(id)!!.copy(status = BatchStatus.RUNNING))
        converter.requests.clear()

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(converter.requests.map { it.sourceUri }).containsExactly(two.sourceUri)
        assertThat(files.names(folder)).containsExactly("One.aiff", "Two.aiff")
    }

    @Test
    fun `a finished batch is not run again`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1")
        runner().run(id)
        converter.requests.clear()

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(converter.requests).isEmpty()
        assertThat(runner().run(404)).isNull()
    }

    @Test
    fun `files run in parallel up to the budget's concurrency`() = runBlocking<Unit> {
        budgets = FakeBudgets(concurrentFiles = 2)
        converter.stepDelayMs = 20
        val id = batchOf("1.flac" to "1", "2.flac" to "2", "3.flac" to "3", "4.flac" to "4", "5.flac" to "5")

        runner().run(id)

        assertThat(converter.maxRunning).isEqualTo(2)
        assertThat(repository.getBatch(id)!!.counts.done).isEqualTo(5)
    }

    @Test
    fun `a budget of one file runs them one after another`() = runBlocking<Unit> {
        budgets = FakeBudgets(concurrentFiles = 1, pauseMs = 1)
        converter.stepDelayMs = 5
        val id = batchOf("1.flac" to "1", "2.flac" to "2", "3.flac" to "3")

        runner().run(id)

        assertThat(converter.maxRunning).isEqualTo(1)
        assertThat(converter.requests.map { it.sourceUri })
            .containsExactly(repository.item(id, 0).sourceUri, repository.item(id, 1).sourceUri, repository.item(id, 2).sourceUri)
            .inOrder()
    }

    @Test
    fun `progress counts finished files and names the current one`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "corrupt")
        val updates = mutableListOf<BatchProgress>()

        runner().run(id) { updates += it }

        assertThat(updates.first().currentFileName).isEqualTo("One.flac")
        assertThat(updates.map { it.currentFileName }).contains("Two.flac")
        val last = updates.last()
        assertThat(last.finished).isEqualTo(2)
        assertThat(last.total).isEqualTo(2)
        assertThat(last.failed).isEqualTo(1)
        assertThat(last.fraction).isEqualTo(1f)
        assertThat(updates.map { it.fraction }).isInOrder()
    }

    @Test
    fun `progress names a file still converting, never one that already finished`() = runBlocking<Unit> {
        // T53: "7 of 8 - 08.flac" while 08 was done and 07, started before it, was the one still converting.
        budgets = FakeBudgets(concurrentFiles = 2)
        val id = batchOf("07 Lakes.flac" to "7", "08 Dub.flac" to "8")
        val gate = CompletableDeferred<Unit>()
        converter.gates[repository.item(id, 0).sourceUri] = gate
        val updates = mutableListOf<BatchProgress>()

        val job = launch { runner().run(id) { updates += it } }
        awaitUntil { updates.any { it.finished == 1 } }
        val afterDub = updates.first { it.finished == 1 }
        gate.complete(Unit)
        job.join()

        assertThat(afterDub.currentFileName).isEqualTo("07 Lakes.flac")
        assertThat(afterDub.total).isEqualTo(2)
    }

    @Test
    fun `a file that stops responding fails with a reason and the batch completes`() = runBlocking<Unit> {
        budgets = FakeBudgets(concurrentFiles = 2)
        val id = batchOf("06.flac" to "6", "07.flac" to "7", "08.flac" to "8")
        converter.gates[repository.item(id, 1).sourceUri] = CompletableDeferred() // never completes
        val updates = mutableListOf<BatchProgress>()

        val batch = withTimeout(5_000) { runner(stallTimeout = 100.milliseconds).run(id) { updates += it } }!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(batch.counts.done).isEqualTo(2)
        assertThat(batch.counts.failed).isEqualTo(1)
        val stuck = repository.item(id, 1)
        assertThat(stuck.state).isEqualTo(BatchItemState.FAILED)
        assertThat(stuck.failureReason).contains("stopped responding")
        // Its part file was deleted; the others took their names.
        assertThat(files.names(batchFolder())).containsExactly("06.aiff", "08.aiff")
        assertThat(updates.last().finished).isEqualTo(3)
        assertThat(updates.last().failed).isEqualTo(1)
    }

    @Test
    fun `a file stuck in a call that ignores cancellation is given up, and its late end changes nothing`() = runBlocking<Unit> {
        budgets = FakeBudgets(concurrentFiles = 2)
        val id = batchOf("07.flac" to "7", "08.flac" to "8")
        val stuck = CompletableDeferred<Unit>()
        converter.stuckGates[repository.item(id, 0).sourceUri] = stuck
        val unblock = launch { delay(3_000); stuck.complete(Unit) } // a run that waits for it still ends

        val batch = withTimeout(5_000) { runner(stallTimeout = 100.milliseconds).run(id) }!!
        unblock.cancel()

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.FAILED)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.DONE)

        // The blocked call returns long after: the given-up file deletes its part file and leaves the history alone.
        stuck.complete(Unit)
        awaitUntil { files.names(batchFolder()) == listOf("08.aiff") }
        delay(50)
        assertThat(files.names(batchFolder())).containsExactly("08.aiff")
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.FAILED)
        assertThat(repository.item(id, 0).failureReason).contains("stopped responding")
    }

    @Test
    fun `cancelling does not wait forever for a file stuck in a call that ignores cancellation`() = runBlocking<Unit> {
        val id = batchOf("07.flac" to "7", "08.flac" to "8")
        val stuck = CompletableDeferred<Unit>()
        converter.stuckGates[repository.item(id, 0).sourceUri] = stuck

        val job = launch { runner(stallTimeout = 1_000.milliseconds).run(id) }
        awaitUntil { converter.running == 1 }
        try {
            withTimeout(2_500) { job.cancelAndJoin() }
        } finally {
            stuck.complete(Unit) // a cancel that waits for it still ends
        }
        val batch = runner().finishInterrupted(id, BatchStatus.CANCELLED)!!

        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.SKIPPED)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.SKIPPED)
        assertThat(files.names(batchFolder())).isEmpty()
    }

    @Test
    fun `with overwrite on, each verified output replaces its original`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.aiff" to "2", overwrite = true)

        val batch = runner().run(id)!!

        assertThat(batch.counts.replacedOriginals).isEqualTo(2)
        assertThat(files.names(music)).containsExactly("One.aiff", "Two.aiff")
        // Every output moved next to its original: the emptied batch folder goes too.
        assertThat(files.names(converted())).isEmpty()
        assertThat(repository.getBatch(id)!!.counts.replacedOriginals).isEqualTo(2)
        assertThat(files.content(repository.item(id, 1).outputUri!!)).isEqualTo("AIFF:2")
    }

    @Test
    fun `replace originals later moves only the outputs still in the batch folder`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Bad.flac" to "corrupt", "Three.mp3" to "3")
        runner().run(id)
        val updates = mutableListOf<BatchProgress>()

        val batch = ReplaceBatchOriginals(repository, OriginalReplacer(files, access, converter, repository))(id) { updates += it }!!

        assertThat(batch.counts.replacedOriginals).isEqualTo(2)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.FAILED)
        assertThat(files.names(music)).containsExactly("One.aiff", "Bad.flac", "Three.aiff")
        assertThat(files.names(converted())).isEmpty()
        assertThat(updates.last().finished).isEqualTo(2)
        assertThat(updates.last().total).isEqualTo(2)
    }

    @Test
    fun `replacing originals of a batch still running does nothing`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1")

        val batch = ReplaceBatchOriginals(repository, OriginalReplacer(files, access, converter, repository))(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.QUEUED)
        assertThat(files.names(music)).containsExactly("One.flac")
    }

    @Test
    fun `cancelling while an original is replaced still records where the converted file is`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", overwrite = true)
        val source = repository.item(id, 0).sourceUri
        lateinit var job: Job
        files.onDelete = { uri -> if (uri == source) job.cancel() }

        job = launch(start = CoroutineStart.LAZY) { runner().run(id) }
        job.join()

        val item = repository.item(id, 0)
        assertThat(item.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(item.outputUri).isEqualTo(files.uriOf(music, "One.aiff"))
        assertThat(files.content(item.outputUri!!)).isEqualTo("AIFF:1")
    }

    @Test
    fun `a replacement that throws keeps the verified output on record`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", overwrite = true)
        access.failure = IllegalStateException("provider died")

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        val item = repository.item(id, 0)
        assertThat(item.state).isEqualTo(BatchItemState.FAILED)
        assertThat(item.outputUri).isEqualTo(files.uriOf(batchFolder(), "One.aiff"))
        assertThat(item.canReplaceOriginal).isTrue()
        assertThat(files.names(music)).containsExactly("One.flac")
        assertThat(batch.counts.notReplaced).isEqualTo(1)
    }

    @Test
    fun `a file whose start cannot be recorded fails alone`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "2")
        val one = repository.item(id, 0).id
        var failed = false
        repository.failItemWrite = { item ->
            if (item.id == one && item.state == BatchItemState.RUNNING && !failed) {
                failed = true
                IOException("database is locked")
            } else {
                null
            }
        }

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.FAILED)
        assertThat(repository.item(id, 0).failureReason).contains("database is locked")
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.DONE)
    }

    @Test
    fun `a budget of no files still converts, one at a time`() = runBlocking<Unit> {
        budgets = FakeBudgets(concurrentFiles = 0)
        val id = batchOf("One.flac" to "1", "Two.flac" to "2")

        val batch = withTimeout(5_000) { runner().run(id)!! }

        assertThat(batch.counts.done).isEqualTo(2)
        assertThat(converter.maxRunning).isEqualTo(1)
    }

    @Test
    fun `outputs of very long names fit FAT, part suffix and numbering included`() = runBlocking<Unit> {
        val long = "Ä".repeat(260)
        val id = repository.create(
            newBatch(),
            listOf("a", "b").map { BatchSource(files.file(music, it, it), "$long.flac", music) },
        )

        runner().run(id)

        val names = files.names(batchFolder())
        assertThat(names).hasSize(2)
        assertThat(names.all { it.length <= BatchNaming.MAX_NAME_BYTES && it.endsWith(".aiff") }).isTrue()
        assertThat(repository.item(id, 1).outputName).endsWith(" (2).aiff")
    }

    @Test
    fun `a cancel that wins the race for a queued batch keeps the job from running it`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1")
        val cancel = CancelConversionBatch(NoScheduler, runner())
        var raced = false
        // The job has just read its queued batch when the user cancels it.
        repository.afterGetBatch = { if (!raced) { raced = true; cancel(id) } }

        val batch = runner().run(id)!!

        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.SKIPPED)
        assertThat(converter.requests).isEmpty()
    }

    @Test
    fun `a cancel landing after the last file but before the batch completes wins, keeping the converted files`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "2")
        val cancel = CancelConversionBatch(NoScheduler, runner())
        var raced = false
        // Every file has its outcome; the user's cancel ends the batch just before the run records it completed.
        repository.beforeTransition = { batchId, to ->
            if (to == BatchStatus.COMPLETED && !raced) {
                raced = true
                cancel(batchId)
            }
        }

        val batch = runner().run(id)!!

        assertThat(raced).isTrue()
        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(repository.getItems(id).map { it.state }.toSet()).containsExactly(BatchItemState.DONE)
        assertThat(batch.counts.done).isEqualTo(2)
        assertThat(files.names(batchFolder())).containsExactly("One.aiff", "Two.aiff")
    }

    @Test
    fun `a job cancelled after the last file still removes the folder its replacements emptied`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.aiff" to "2", overwrite = true)
        lateinit var job: Job
        // The job is stopped once every file ended, before the completion is written (Room hops cancellably).
        repository.beforeTransition = { _, to ->
            if (to == BatchStatus.COMPLETED) {
                job.cancel()
                yield()
            }
        }

        job = launch(start = CoroutineStart.LAZY) { runner().run(id) }
        job.join()
        repository.beforeTransition = { _, _ -> }
        // What the job does after the user's cancel stopped it.
        val batch = runner().finishInterrupted(id, BatchStatus.CANCELLED)!!

        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(batch.counts.replacedOriginals).isEqualTo(2)
        assertThat(files.names(music)).containsExactly("One.aiff", "Two.aiff")
        // Every output moved next to its original: the emptied batch folder goes, as when the batch completes.
        assertThat(files.names(converted())).isEmpty()
    }

    @Test
    fun `an interrupted batch whose folder is gone still ends`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1")
        repository.updateBatch(repository.getBatch(id)!!.copy(status = BatchStatus.RUNNING, outputFolderUri = "doc:gone"))

        val batch = runner().finishInterrupted(id, BatchStatus.CANCELLED)!!

        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.SKIPPED)
    }

    @Test
    fun `a batch that failed unexpectedly ends as failed, its part files deleted and files left skipped`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "2")
        runner().run(id)
        val folder = batchFolder()
        files.file(folder, "Two.aiff.part", "half")
        repository.updateItem(repository.item(id, 1).copy(state = BatchItemState.RUNNING))
        repository.updateBatch(repository.getBatch(id)!!.copy(status = BatchStatus.RUNNING))

        val batch = runner().finishFailed(id, "Conversion failed (boom)")!!

        assertThat(batch.status).isEqualTo(BatchStatus.FAILED)
        assertThat(batch.failureReason).isEqualTo("Conversion failed (boom)")
        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.DONE)
        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.SKIPPED)
        assertThat(files.names(folder)).doesNotContain("Two.aiff.part")
        // A batch already ended keeps its ending.
        assertThat(runner().finishFailed(id, "again")!!.failureReason).isEqualTo("Conversion failed (boom)")
    }

    @Test
    fun `replace originals later records each replacement even when cancelled mid-way`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1")
        runner().run(id)
        val source = repository.item(id, 0).sourceUri
        lateinit var job: Job
        files.onDelete = { uri -> if (uri == source) job.cancel() }

        job = launch(start = CoroutineStart.LAZY) { ReplaceBatchOriginals(repository, OriginalReplacer(files, access, converter, repository))(id) }
        job.join()

        val item = repository.item(id, 0)
        assertThat(item.state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(item.outputUri).isEqualTo(files.uriOf(music, "One.aiff"))
    }

    @Test
    fun `a batch folder still holding an output whose original was kept stays`() = runBlocking<Unit> {
        val elsewhere = files.folder("Elsewhere")
        val id = repository.create(
            newBatch(),
            listOf(
                BatchSource(files.file(music, "One.flac", "1"), "One.flac", music),
                BatchSource(files.file(elsewhere, "Two.flac", "2"), "Two.flac", elsewhere), // no write access there
            ),
        )
        runner().run(id)

        ReplaceBatchOriginals(repository, OriginalReplacer(files, access, converter, repository))(id)

        assertThat(repository.item(id, 1).state).isEqualTo(BatchItemState.FAILED)
        assertThat(files.names(batchFolder())).containsExactly("Two.aiff")
    }

    @Test
    fun `a batch folder holding anything else is never deleted`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1")
        runner().run(id)
        files.file(batchFolder(), "notes.txt", "mine") // put there by the user

        ReplaceBatchOriginals(repository, OriginalReplacer(files, access, converter, repository))(id)

        assertThat(repository.item(id, 0).state).isEqualTo(BatchItemState.REPLACED_ORIGINAL)
        assertThat(files.names(batchFolder())).containsExactly("notes.txt")
    }

    @Test
    fun `an ending whose clean-up broke is completed by the next ending`() = runBlocking<Unit> {
        val id = batchOf("One.flac" to "1", "Two.flac" to "2")
        repository.updateBatch(repository.getBatch(id)!!.copy(status = BatchStatus.RUNNING))
        // The database breaks right after the status moved: the files left cannot be marked skipped.
        repository.failItemWrite = { item -> if (item.state == BatchItemState.SKIPPED) IOException("disk I/O error") else null }
        runCatching { runner().finishInterrupted(id, BatchStatus.CANCELLED) }
        assertThat(repository.getBatch(id)!!.status).isEqualTo(BatchStatus.CANCELLED)
        repository.failItemWrite = { null }

        val batch = runner().finishInterrupted(id, BatchStatus.CANCELLED)!!

        assertThat(batch.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(repository.getItems(id).map { it.state }.toSet()).containsExactly(BatchItemState.SKIPPED)
    }

    private object NoScheduler : io.sonora.core.domain.port.ConversionBatchScheduler {
        override fun enqueueConversion(batchId: Long) = Unit
        override fun enqueueReplacement(batchId: Long) = Unit
        override suspend fun cancel(batchId: Long) = false
        override fun progress(batchId: Long) = kotlinx.coroutines.flow.flowOf<BatchProgress?>(null)
    }
}
