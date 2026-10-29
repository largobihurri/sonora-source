package io.sonora.feature.converter

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.CancelConversionBatch
import io.sonora.core.domain.conversion.batch.ObserveConversionHistory
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.ResolveConvertedTracks
import io.sonora.core.domain.conversion.batch.StartConversionBatch
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.OutputRootFolder
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class ConverterViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)
    private val folder = OutputRootFolder("content://tree/music", "Music")
    private val djFolder = PendingSource(PickedSource.Folder("content://tree/dj"), "DJ")
    private val track = PendingSource(PickedSource.File("content://doc/track.mp3"), "track.mp3")
    private val djSources = listOf(
        BatchSource("content://doc/a.flac", "a.flac", "content://tree/dj"),
        BatchSource("content://doc/b.mp3", "b.mp3", "content://tree/dj"),
    )

    private val repository = FakeBatchRepository()
    private val scheduler = FakeScheduler()
    private val outputRoot = FakeOutputRoot()
    private val expander = FakeExpander(djSources)
    private val convertedFiles = FakeConvertedFiles()
    private val playback = RecordingPlayback()
    private val pending = PendingConversionSources()
    private lateinit var viewModel: ConverterViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = newViewModel()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = ConverterViewModel(
        StartConversionBatch(expander, repository, outputRoot, GrantingAccess, scheduler, clock),
        CancelConversionBatch(scheduler, runnerFor(repository, outputRoot)),
        ObserveConversionHistory(repository, clock),
        outputRoot,
        scheduler,
        repository,
        ResolveConvertedTracks(repository, convertedFiles),
        playback,
        pending,
        expander,
        FakePreferences(),
        GrantingAccess,
    )

    private val state get() = viewModel.state.value

    /** Starts a batch of [djFolder] and returns its id. */
    private fun startBatch(overwrite: Boolean = false): Long {
        outputRoot.root.value = folder
        viewModel.addSources(listOf(djFolder))
        if (overwrite) {
            viewModel.setOverwriteOriginals(true)
            viewModel.confirmReplace()
        }
        viewModel.convert()
        return scheduler.conversions.last()
    }

    /** Runs batch [id] to [status] with [items], as the job would: files first, then the batch. */
    private fun finish(id: Long, status: BatchStatus, items: (List<ConversionBatchItem>) -> List<ConversionBatchItem>) {
        repository.update(id) { it.copy(status = BatchStatus.RUNNING) }
        repository.setItems(id, items(repository.itemsOf(id)))
        repository.update(id) { it.copy(status = status) }
    }

    // Convert button

    @Test
    fun `convert is disabled until there is an output folder and a source`() {
        assertThat(state.canConvert).isFalse()

        viewModel.addSources(listOf(djFolder))
        assertThat(state.canConvert).isFalse()

        outputRoot.root.value = folder
        assertThat(state.canConvert).isTrue()

        viewModel.removeSource(djFolder)
        assertThat(state.canConvert).isFalse()
    }

    @Test
    fun `convert is disabled while a batch runs, even with new sources`() {
        startBatch()

        viewModel.addSources(listOf(track))

        assertThat(state.activeBatch?.isRunning).isTrue()
        assertThat(state.canConvert).isFalse()
    }

    @Test
    fun `adding the same pick twice keeps one row`() {
        viewModel.addSources(listOf(djFolder, track))
        viewModel.addSources(listOf(djFolder.copy(displayName = "DJ again")))

        assertThat(state.sources).containsExactly(djFolder, track).inOrder()
        assertThat(state.hasIndividualFiles).isTrue()
    }

    // StartBatchResult

    @Test
    fun `songs sent from the Library show as sources and start like any pick`() {
        outputRoot.root.value = folder
        val song = PendingSource(PickedSource.Track("content://tree/music/document/a.flac"), "Song A", detail = "Artist")

        pending.add(listOf(song))

        assertThat(state.sources).containsExactly(song)
        assertThat(state.canConvert).isTrue()
        viewModel.convert()
        assertThat(expander.lastPicks).containsExactly(song.pick)
        assertThat(pending.sources.value).isEmpty()
    }

    @Test
    fun `songs arriving from the Library ask once to bring the sources into view`() {
        assertThat(viewModel.sourcesRevealRequested.value).isFalse()

        viewModel.revealSources()
        assertThat(viewModel.sourcesRevealRequested.value).isTrue()

        viewModel.sourcesRevealHandled()
        assertThat(viewModel.sourcesRevealRequested.value).isFalse()
    }

    @Test
    fun `the converter's picks and removals change the one shared list`() {
        viewModel.addSources(listOf(djFolder, track))
        assertThat(pending.sources.value).containsExactly(djFolder, track).inOrder()

        viewModel.removeSource(djFolder)
        assertThat(pending.sources.value).containsExactly(track)
        // A converter screen made later (another view model) still finds them.
        assertThat(newViewModel().state.value.sources).containsExactly(track)
    }

    @Test
    fun `a started batch becomes the active batch and clears the picks and the overwrite switch`() {
        val id = startBatch(overwrite = true)

        assertThat(state.activeBatch?.batch?.id).isEqualTo(id)
        assertThat(state.activeBatch?.batch?.overwriteOriginals).isTrue()
        assertThat(state.sources).isEmpty()
        assertThat(state.overwriteOriginals).isFalse()
        assertThat(state.starting).isFalse()
        assertThat(viewModel.notice.value).isNull()
    }

    @Test
    fun `the batch runs with the chosen profile, mode and picks`() {
        viewModel.setMode(PerformanceMode.LOW_RESOURCES)
        val id = startBatch()

        val batch = state.activeBatch!!.batch
        assertThat(batch.id).isEqualTo(id)
        assertThat(batch.performanceMode).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(batch.profileId).isEqualTo(ConversionProfiles.XDJ_700.id)
        assertThat(expander.lastPicks).containsExactly(djFolder.pick)
    }

    @Test
    fun `no output folder shows its notice and keeps the picks`() {
        viewModel.addSources(listOf(djFolder))

        viewModel.convert()

        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.NO_OUTPUT_FOLDER)
        assertThat(state.sources).containsExactly(djFolder)
        assertThat(state.activeBatch).isNull()
        assertThat(scheduler.conversions).isEmpty()
    }

    @Test
    fun `picks without audio files show their notice and stay for editing`() {
        expander.sources = emptyList()
        outputRoot.root.value = folder
        viewModel.addSources(listOf(djFolder))

        viewModel.convert()

        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.NO_AUDIO_FILES)
        assertThat(state.sources).containsExactly(djFolder)
        assertThat(state.canConvert).isTrue()
    }

    @Test
    fun `a start that fails shows a notice and allows another try`() {
        expander.failure = IOException("revoked")
        outputRoot.root.value = folder
        viewModel.addSources(listOf(djFolder))

        viewModel.convert()

        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.START_FAILED)
        assertThat(state.starting).isFalse()
        assertThat(state.canConvert).isTrue()
        viewModel.dismissNotice()
        assertThat(viewModel.notice.value).isNull()
    }

    @Test
    fun `sources added while a start is in flight stay for the next batch`() {
        val gate = CompletableDeferred<Unit>()
        expander.gate = gate
        outputRoot.root.value = folder
        viewModel.addSources(listOf(djFolder))

        viewModel.convert()
        assertThat(state.starting).isTrue()
        viewModel.addSources(listOf(track))
        gate.complete(Unit)

        assertThat(expander.lastPicks).containsExactly(djFolder.pick)
        assertThat(state.activeBatch).isNotNull()
        assertThat(state.sources).containsExactly(track)
        assertThat(state.starting).isFalse()
    }

    @Test
    fun `convert does nothing without sources, while starting, or while a batch runs`() {
        outputRoot.root.value = folder
        viewModel.convert()
        assertThat(expander.lastPicks).isEmpty()
        assertThat(viewModel.notice.value).isNull()

        val gate = CompletableDeferred<Unit>()
        expander.gate = gate
        viewModel.addSources(listOf(djFolder))
        viewModel.convert()
        viewModel.convert() // a second tap while the first is being handled
        gate.complete(Unit)
        assertThat(scheduler.conversions).hasSize(1)

        expander.gate = null
        viewModel.addSources(listOf(track))
        viewModel.convert() // the first batch still runs
        assertThat(scheduler.conversions).hasSize(1)
        assertThat(state.sources).containsExactly(track)
    }

    // Opening a batch

    @Test
    fun `opening a batch from its notification asks to reveal it, even when it is already shown`() {
        val id = startBatch()
        assertThat(viewModel.revealRequested.value).isTrue()
        viewModel.revealHandled()
        assertThat(viewModel.revealRequested.value).isFalse()

        viewModel.showBatch(id)

        assertThat(viewModel.revealRequested.value).isTrue()
        assertThat(state.activeBatch?.batch?.id).isEqualTo(id)
    }

    @Test
    fun `a reveal already handled is not asked again by a recreated screen`() {
        startBatch()
        viewModel.revealHandled()

        // The process was killed and the screen restored: nothing new asks to scroll.
        val restored = newViewModel()

        assertThat(viewModel.revealRequested.value).isFalse()
        assertThat(restored.revealRequested.value).isFalse()
    }

    @Test
    fun `a reveal not handled yet waits for the screen, and is not restored after process death`() {
        val id = startBatch()

        // The screen was recreated (the tab slid in again) before it scrolled: the request is still there.
        assertThat(viewModel.revealRequested.value).isTrue()

        // The reveal lives in the view model only: a restored one shows the running batch without scrolling to it.
        val restored = newViewModel()

        assertThat(restored.revealRequested.value).isFalse()
        assertThat(restored.state.value.activeBatch?.batch?.id).isEqualTo(id)
    }

    // Cancel and delete failures

    @Test
    fun `a cancel that fails shows a notice instead of crashing`() {
        startBatch()
        scheduler.cancelFailure = IllegalStateException("WorkManager is not initialized")

        viewModel.cancelActiveBatch()

        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.CANCEL_FAILED)
    }

    @Test
    fun `a delete that fails shows a notice and keeps the batch`() {
        val id = startBatch()
        finish(id, BatchStatus.COMPLETED) { items -> items.map { it.converted() } }
        repository.deleteFailure = IOException("database is locked")

        viewModel.deleteFromHistory(id)

        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.DELETE_FAILED)
        assertThat(repository.has(id)).isTrue()
    }

    // Output folder

    @Test
    fun `a picked output folder is stored`() {
        viewModel.setOutputFolder("content://tree/usb")

        assertThat(state.outputFolder?.treeUri).isEqualTo("content://tree/usb")
    }

    @Test
    fun `an output folder without write access shows a notice`() {
        outputRoot.failure = SecurityException("read only")

        viewModel.setOutputFolder("content://tree/usb")

        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.OUTPUT_FOLDER_FAILED)
        assertThat(state.outputFolder).isNull()
    }

    // Overwrite originals before the batch

    @Test
    fun `turning overwrite on asks first and stays off until confirmed`() {
        viewModel.setOverwriteOriginals(true)

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.BeforeBatch)
        assertThat(state.overwriteOriginals).isFalse()

        viewModel.confirmReplace()

        assertThat(state.overwriteOriginals).isTrue()
        assertThat(viewModel.prompt.value).isNull()
    }

    @Test
    fun `dismissing the overwrite warning leaves it off`() {
        viewModel.setOverwriteOriginals(true)
        viewModel.dismissReplacePrompt()

        assertThat(state.overwriteOriginals).isFalse()
        assertThat(viewModel.prompt.value).isNull()
    }

    @Test
    fun `turning overwrite off needs no confirmation`() {
        viewModel.setOverwriteOriginals(true)
        viewModel.confirmReplace()

        viewModel.setOverwriteOriginals(false)

        assertThat(state.overwriteOriginals).isFalse()
        assertThat(viewModel.prompt.value).isNull()
    }

    // Running batch

    @Test
    fun `live progress of the active batch comes from the scheduler`() {
        val id = startBatch()
        val progress = BatchProgress(1, 2, "b.mp3", 0.6f)

        scheduler.progress.value = mapOf(id to progress)

        assertThat(state.activeBatch?.progress).isEqualTo(progress)
    }

    @Test
    fun `cancel stops the active batch`() {
        val id = startBatch()

        viewModel.cancelActiveBatch()

        assertThat(scheduler.cancelled).containsExactly(id)
        assertThat(state.activeBatch?.batch?.status).isEqualTo(BatchStatus.CANCELLED)
        assertThat(state.activeBatch?.isRunning).isFalse()
    }

    @Test
    fun `a batch still running when the screen opens becomes the active batch`() {
        repository.put(batch(7, BatchStatus.RUNNING))

        viewModel = newViewModel()

        assertThat(state.activeBatch?.batch?.id).isEqualTo(7)
    }

    @Test
    fun `a batch opened from its notification becomes the active batch`() {
        repository.put(batch(3, BatchStatus.COMPLETED))

        viewModel.showBatch(3)

        assertThat(state.activeBatch?.batch?.id).isEqualTo(3)
        viewModel.dismissActiveBatch()
        assertThat(state.activeBatch).isNull()
    }

    @Test
    fun `a running batch cannot be dismissed`() {
        startBatch()

        viewModel.dismissActiveBatch()

        assertThat(state.activeBatch).isNotNull()
    }

    // Replace originals after the batch

    @Test
    fun `a batch that finishes with replaceable files asks once to replace them`() {
        val id = startBatch()

        finish(id, BatchStatus.COMPLETED) { items -> items.map { it.converted() } }

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.AfterBatch(id, 2, lossyCount = 0))
        viewModel.dismissReplacePrompt()
        repository.update(id) { it.copy(failureReason = "touched") } // any later change of the batch
        assertThat(viewModel.prompt.value).isNull()
        assertThat(scheduler.replacements).isEmpty()
    }

    @Test
    fun `the after-batch prompt counts lossless originals a lossy file would replace`() {
        val id = startBatch()

        // a.flac and b.mp3 both went to MP3: only the FLAC original is lossless.
        finish(id, BatchStatus.COMPLETED) { items -> items.map { it.converted().copy(profileId = "out:mp3:cbr:320") } }

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.AfterBatch(id, 2, lossyCount = 1))
    }

    @Test
    fun `replacing from a batch's summary or history also counts lossless originals turned lossy`() {
        val id = startBatch()
        finish(id, BatchStatus.CANCELLED) { items -> items.map { it.converted().copy(profileId = "out:aac:256") } }
        assertThat(viewModel.prompt.value).isNull()

        viewModel.requestReplaceOriginals(state.activeBatch!!)

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.AfterBatch(id, 2, lossyCount = 1))
    }

    @Test
    fun `a batch watched to the end asks once to replace originals, after its job is gone`() {
        // The real order of events: the job publishes progress, the history says COMPLETED while the job still runs
        // (its last progress stays until the work ends), then the job's progress goes away.
        val id = startBatch()
        scheduler.progress.value = mapOf(id to BatchProgress(0, 2, "a.flac", 0f))
        repository.update(id) { it.copy(status = BatchStatus.RUNNING) }
        repository.setItems(id, repository.itemsOf(id).map { it.converted() })
        scheduler.progress.value = mapOf(id to BatchProgress(2, 2, null, 1f))
        repository.update(id) { it.copy(status = BatchStatus.COMPLETED) }
        assertThat(viewModel.prompt.value).isNull()

        scheduler.progress.value = emptyMap()

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.AfterBatch(id, 2, lossyCount = 0))
        viewModel.dismissReplacePrompt()
        scheduler.progress.value = mapOf(id to BatchProgress(2, 2, null, 1f))
        scheduler.progress.value = emptyMap()
        assertThat(viewModel.prompt.value).isNull()
    }

    @Test
    fun `a batch whose files the history lists late still asks once they are in`() {
        val id = startBatch()
        repository.update(id) { it.copy(status = BatchStatus.RUNNING) }
        repository.update(id) { it.copy(status = BatchStatus.COMPLETED) } // the files' outcome not read yet
        assertThat(viewModel.prompt.value).isNull()

        repository.setItems(id, repository.itemsOf(id).map { it.converted() })

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.AfterBatch(id, 2, lossyCount = 0))
    }

    @Test
    fun `once replaced, the summary tells how many originals were replaced`() {
        val id = startBatch()
        finish(id, BatchStatus.COMPLETED) { items -> items.map { it.converted() } }
        viewModel.confirmReplace()
        scheduler.progress.value = mapOf(id to BatchProgress(0, 2, "a.flac", 0f))
        assertThat(state.activeBatch?.replacedCount).isEqualTo(0)

        repository.setItems(id, repository.itemsOf(id).map { it.copy(state = BatchItemState.REPLACED_ORIGINAL) })
        scheduler.progress.value = emptyMap()

        assertThat(state.activeBatch?.replacedCount).isEqualTo(2)
        assertThat(state.activeBatch?.replaceableCount).isEqualTo(0)
        assertThat(viewModel.prompt.value).isNull()
    }

    @Test
    fun `subfolders that could not be read are shown with the batch they were left out of`() {
        expander.skippedFolders = 2
        val id = startBatch()

        assertThat(state.activeBatch?.batch?.id).isEqualTo(id)
        assertThat(state.skippedFolders).isEqualTo(2)

        finish(id, BatchStatus.COMPLETED) { items -> items.map { it.copy(state = BatchItemState.FAILED) } }
        viewModel.dismissActiveBatch()
        assertThat(state.skippedFolders).isEqualTo(0)
    }

    @Test
    fun `confirming the after-batch prompt enqueues the replacement`() {
        val id = startBatch()
        finish(id, BatchStatus.COMPLETED) { items -> items.map { it.converted() } }

        viewModel.confirmReplace()

        assertThat(scheduler.replacements).containsExactly(id)
        assertThat(viewModel.prompt.value).isNull()
    }

    @Test
    fun `a batch that replaced its originals itself does not ask and lists them`() {
        val id = startBatch(overwrite = true)

        finish(id, BatchStatus.COMPLETED) { items ->
            listOf(items[0].converted().copy(state = BatchItemState.REPLACED_ORIGINAL), items[1].converted())
        }

        assertThat(viewModel.prompt.value).isNull()
        assertThat(state.activeBatch?.replacedItems?.map { it.sourceName }).containsExactly("a.flac")
    }

    @Test
    fun `a batch without replaceable files does not ask`() {
        val id = startBatch()

        finish(id, BatchStatus.COMPLETED) { items ->
            items.map { it.copy(state = BatchItemState.FAILED, failureReason = "corrupt") }
        }

        assertThat(viewModel.prompt.value).isNull()
        assertThat(state.activeBatch?.replaceableCount).isEqualTo(0)
    }

    @Test
    fun `individually picked files are not offered for replacement`() {
        val id = startBatch()

        finish(id, BatchStatus.COMPLETED) { items -> items.map { it.converted().copy(sourceParentUri = null) } }

        assertThat(viewModel.prompt.value).isNull()
    }

    @Test
    fun `a cancelled batch does not ask but still offers the replacement`() {
        val id = startBatch()

        finish(id, BatchStatus.CANCELLED) { items -> listOf(items[0].converted(), items[1].copy(state = BatchItemState.SKIPPED)) }

        assertThat(viewModel.prompt.value).isNull()
        assertThat(state.activeBatch?.replaceableCount).isEqualTo(1)
        viewModel.requestReplaceOriginals(state.activeBatch!!)
        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.AfterBatch(id, 1, lossyCount = 0))
    }

    @Test
    fun `a finished batch opened later does not ask by itself`() {
        repository.put(batch(4, BatchStatus.COMPLETED), listOf(item(4, 0).converted()))

        viewModel.showBatch(4)

        assertThat(viewModel.prompt.value).isNull()
        assertThat(state.activeBatch?.replaceableCount).isEqualTo(1)
    }

    @Test
    fun `nothing is replaceable while the replacement runs`() {
        repository.put(batch(4, BatchStatus.COMPLETED), listOf(item(4, 0).converted()))
        viewModel.showBatch(4)

        scheduler.progress.value = mapOf(4L to BatchProgress(0, 1, "a.flac", 0f))

        assertThat(state.activeBatch?.isReplacing).isTrue()
        assertThat(state.activeBatch?.replaceableCount).isEqualTo(0)
        assertThat(state.canConvert).isFalse()
    }

    // History

    @Test
    fun `the history lists batches in date sections`() {
        repository.put(batch(1, BatchStatus.COMPLETED, createdAt = "2026-10-03T09:00:00Z"))
        repository.put(batch(2, BatchStatus.FAILED, createdAt = "2026-09-01T09:00:00Z"))

        assertThat(state.history.map { section -> section.entries.map { it.batch.id } })
            .containsExactly(listOf(1L), listOf(2L)).inOrder()
    }

    @Test
    fun `deleting from history forgets the batch and clears it when shown`() {
        repository.put(batch(5, BatchStatus.COMPLETED))
        viewModel.showBatch(5)

        viewModel.deleteFromHistory(5)

        assertThat(repository.has(5)).isFalse()
        assertThat(state.activeBatch).isNull()
        assertThat(state.history).isEmpty()
    }

    @Test
    fun `a running batch is never deleted from history`() {
        repository.put(batch(6, BatchStatus.RUNNING))

        viewModel.deleteFromHistory(6)

        assertThat(repository.has(6)).isTrue()
    }

    @Test
    fun `the batch detail tells how many originals were replaced`() = runTest {
        repository.put(batch(9, BatchStatus.COMPLETED), listOf(item(9, 0).converted().copy(state = BatchItemState.REPLACED_ORIGINAL), item(9, 1).converted()))

        val detail = viewModel.batchDetail(9).first()

        assertThat(detail?.replacedCount).isEqualTo(1)
        assertThat(detail?.replaceableCount).isEqualTo(1)
    }

    @Test
    fun `the batch detail carries its files and what can be replaced`() = runTest {
        repository.put(batch(8, BatchStatus.COMPLETED), listOf(item(8, 0).converted(tags = false), item(8, 1)))

        val detail = viewModel.batchDetail(8).first()

        assertThat(detail?.items?.map { it.sourceName }).containsExactly("f0.flac", "f1.flac").inOrder()
        assertThat(detail?.replaceableCount).isEqualTo(1)
        assertThat(detail?.batch?.counts?.withoutTags).isEqualTo(1)
    }

    // Playing converted files

    /** Batch 9: f0 converted, f1 failed, f2 replaced its original, f3 skipped; every output exists. */
    private fun playableBatch(): List<ConversionBatchItem> {
        val items = listOf(
            item(9, 0).converted(),
            item(9, 1).copy(state = BatchItemState.FAILED, failureReason = "damaged"),
            item(9, 2).copy(state = BatchItemState.REPLACED_ORIGINAL, outputUri = "content://tree/dj/f2.aiff", outputName = "f2.aiff"),
            item(9, 3).copy(state = BatchItemState.SKIPPED),
        )
        repository.put(batch(9, BatchStatus.COMPLETED), items)
        convertedFiles.existing += setOf("content://out/f0.flac", "content://tree/dj/f2.aiff")
        return items
    }

    @Test
    fun `tapping a converted file plays the batch's converted files from it, each from where it is now`() {
        val items = playableBatch()

        viewModel.playItem(items[2])
        viewModel.playItem(items[0])

        assertThat(playback.played).containsExactly(
            "1:content://out/f0.flac,content://tree/dj/f2.aiff",
            "0:content://out/f0.flac,content://tree/dj/f2.aiff",
        ).inOrder()
        assertThat(viewModel.missingFiles.value).isEqualTo(0)
    }

    @Test
    fun `tapping a file after one that is gone starts at the tapped file and says how many are gone`() {
        val items = playableBatch()
        convertedFiles.existing -= "content://out/f0.flac"

        viewModel.playItem(items[2])

        assertThat(playback.played).containsExactly("0:content://tree/dj/f2.aiff")
        assertThat(viewModel.missingFiles.value).isEqualTo(1)
    }

    @Test
    fun `tapping a file that was not converted does nothing`() {
        val items = playableBatch()

        viewModel.playItem(items[1])
        viewModel.playItem(items[3])

        assertThat(playback.played).isEmpty()
        assertThat(convertedFiles.reads).isEmpty()
    }

    @Test
    fun `play all plays the converted files in batch order, from the first`() {
        playableBatch()

        viewModel.playAll(9)

        assertThat(playback.played).containsExactly("0:content://out/f0.flac,content://tree/dj/f2.aiff")
    }

    @Test
    fun `play all skips files that are gone and says how many`() {
        playableBatch()
        convertedFiles.existing -= "content://out/f0.flac"

        viewModel.playAll(9)

        assertThat(playback.played).containsExactly("0:content://tree/dj/f2.aiff")
        assertThat(viewModel.missingFiles.value).isEqualTo(1)
        viewModel.missingFilesShown(1)
        assertThat(viewModel.missingFiles.value).isEqualTo(0)
    }

    @Test
    fun `a missing-files notice stays until the screen shows it, and showing an older one keeps a newer count`() {
        playableBatch()
        convertedFiles.existing -= "content://out/f0.flac"
        viewModel.playAll(9)
        assertThat(viewModel.missingFiles.value).isEqualTo(1)

        // Another play finds more files gone before the screen got to show the first notice.
        convertedFiles.existing.clear()
        viewModel.playAll(9)
        viewModel.missingFilesShown(1)

        assertThat(viewModel.missingFiles.value).isEqualTo(2)
        viewModel.missingFilesShown(2)
        assertThat(viewModel.missingFiles.value).isEqualTo(0)
    }

    @Test
    fun `the latest tap wins, so a play still opening its files is dropped for a tap on another batch`() {
        val items = playableBatch()
        convertedFiles.existing -= "content://tree/dj/f2.aiff"
        val other = item(10, 0).copy(sourceName = "g0.flac").converted()
        repository.put(batch(10, BatchStatus.COMPLETED), listOf(other))
        convertedFiles.existing += "content://out/g0.flac"
        val opening = CompletableDeferred<Unit>()
        convertedFiles.gates["content://out/f0.flac"] = opening

        viewModel.playItem(items[0]) // still opening batch 9's files
        viewModel.playAll(10)
        opening.complete(Unit)

        assertThat(playback.played).containsExactly("0:content://out/g0.flac")
        // The dropped play's gone file is not reported either.
        assertThat(viewModel.missingFiles.value).isEqualTo(0)
    }

    @Test
    fun `the latest tap wins between play all and a tapped file of the same batch`() {
        val items = playableBatch()
        val opening = CompletableDeferred<Unit>()
        convertedFiles.gates["content://out/f0.flac"] = opening

        viewModel.playAll(9)
        viewModel.playItem(items[2])
        opening.complete(Unit)

        assertThat(playback.played).containsExactly("1:content://out/f0.flac,content://tree/dj/f2.aiff")
    }

    @Test
    fun `a tapped file that is gone plays nothing and says so`() {
        val items = playableBatch()
        convertedFiles.existing -= "content://out/f0.flac"

        viewModel.playItem(items[0])

        assertThat(playback.played).isEmpty()
        assertThat(viewModel.missingFiles.value).isEqualTo(1)
    }

    @Test
    fun `when every file is gone, play all plays nothing and counts them`() {
        playableBatch()
        convertedFiles.existing.clear()

        viewModel.playAll(9)

        assertThat(playback.played).isEmpty()
        assertThat(viewModel.missingFiles.value).isEqualTo(2)
    }

    @Test
    fun `a history that cannot be read reports that playing failed`() {
        playableBatch()
        repository.readFailure = IOException("disk")

        viewModel.playAll(9)

        assertThat(playback.played).isEmpty()
        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.PLAY_FAILED)
    }

    @Test
    fun `the batch detail knows how many files can be played`() = runTest {
        playableBatch()

        assertThat(viewModel.batchDetail(9).first()?.playableCount).isEqualTo(2)
    }

    private fun batch(id: Long, status: BatchStatus, createdAt: String = "2026-10-03T10:00:00Z") = ConversionBatch(
        id = id,
        profileId = ConversionProfiles.XDJ_700.id,
        performanceMode = PerformanceMode.NORMAL,
        createdAtMs = Instant.parse(createdAt).toEpochMilli(),
        outputFolderName = "2026-10-03 10.00",
        outputFolderUri = "content://tree/music/Convertidos/2026-10-03 10.00",
        overwriteOriginals = false,
        status = status,
    )

    private fun item(batchId: Long, position: Int) =
        ConversionBatchItem(batchId * 100 + position, batchId, position, "content://doc/f$position", "f$position.flac", "content://tree/dj", profileId = "xdj-700")
}
