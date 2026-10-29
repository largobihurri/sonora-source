package io.sonora.feature.converter

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.batch.CancelConversionBatch
import io.sonora.core.domain.conversion.batch.ObserveConversionHistory
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.ResolveConvertedTracks
import io.sonora.core.domain.conversion.batch.StartConversionBatch
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.ConverterPreferences
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.OutputRootFolder
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The converter's output formats: batch-wide, per source, per file inside a folder, and the lossy warning. */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class ConverterOutputTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-29T12:00:00Z"), ZoneOffset.UTC)
    private val song = PendingSource(PickedSource.Track("content://tree/lib/document/song.flac"), "Song", detail = "Artist")
    private val file = PendingSource(PickedSource.File("content://doc/intro.mp3"), "intro.mp3")
    private val djFolder = PendingSource(PickedSource.Folder("content://tree/dj"), "DJ")
    private val loose = PendingSource(PickedSource.File("content://doc/loose.flac"), "loose.flac")
    private val a = BatchSource("content://tree/dj/document/a.flac", "a.flac", "content://tree/dj")
    private val b = BatchSource("content://tree/dj/document/b.wav", "b.wav", "content://tree/dj")

    private val repository = FakeBatchRepository()
    private val scheduler = FakeScheduler()
    private val outputRoot = FakeOutputRoot(OutputRootFolder("content://tree/music", "Music"))
    private val expander = FakeExpander().apply {
        byPick[song.pick.uri] = listOf(BatchSource(song.pick.uri, "song.flac", "content://tree/lib"))
        byPick[file.pick.uri] = listOf(BatchSource(file.pick.uri, "intro.mp3", null))
        byPick[loose.pick.uri] = listOf(BatchSource(loose.pick.uri, "loose.flac", null))
        byPick[djFolder.pick.uri] = listOf(a, b)
    }
    private val pending = PendingConversionSources()
    private var preferences = FakePreferences()
    private val access = FolderAccess()
    private lateinit var viewModel: ConverterViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = newViewModel()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = ConverterViewModel(
        StartConversionBatch(expander, repository, outputRoot, access, scheduler, clock),
        CancelConversionBatch(scheduler, runnerFor(repository, outputRoot)),
        ObserveConversionHistory(repository, clock),
        outputRoot,
        scheduler,
        repository,
        ResolveConvertedTracks(repository, FakeConvertedFiles()),
        RecordingPlayback(),
        pending,
        expander,
        preferences,
        access,
    )

    private val state get() = viewModel.state.value
    private val output get() = state.output

    private fun overwriteOriginals() {
        viewModel.setOverwriteOriginals(true)
        viewModel.confirmReplace()
    }

    /** Profile id of each file of the last batch, by source URI. */
    private fun lastBatchProfiles(): Map<String, String> =
        repository.itemsOf(scheduler.conversions.last()).associate { it.sourceUri to it.profileId }

    // Batch-wide value and its memory (S6)

    @Test
    fun `the batch-wide format starts from the stored one`() {
        preferences = FakePreferences(AppPreferences(converter = ConverterPreferences("out:flac:24:keep")))

        viewModel = newViewModel()

        assertThat(output.batchWide).isEqualTo(OutputTarget.Lossless(AudioFormat.FLAC, 24, null))
    }

    @Test
    fun `a stored format the picker does not offer starts on the XDJ-700`() {
        preferences = FakePreferences(AppPreferences(converter = ConverterPreferences("watch-opus")))

        viewModel = newViewModel()

        assertThat(output.batchWide).isEqualTo(OutputTarget.Xdj700)
    }

    @Test
    fun `changing the batch-wide format stores it for next time`() {
        viewModel.setBatchWide(OutputTarget.Opus(96))

        assertThat(output.batchWide).isEqualTo(OutputTarget.Opus(96))
        assertThat(preferences.preferences.value.converter.outputProfileId).isEqualTo("out:opus:96")
        assertThat(newViewModel().state.value.output.batchWide).isEqualTo(OutputTarget.Opus(96))
    }

    @Test
    fun `a batch-wide format that cannot be stored still applies to this batch`() {
        preferences.updateFailure = IOException("disk full")

        viewModel.setBatchWide(OutputTarget.Aac(128))

        assertThat(output.batchWide).isEqualTo(OutputTarget.Aac(128))
        assertThat(preferences.preferences.value.converter.outputProfileId).isEqualTo("xdj-700")
    }

    // Songs set by hand (S4)

    @Test
    fun `changing the batch-wide format changes every song still on it and keeps songs set by hand`() {
        viewModel.addSources(listOf(song, file))
        viewModel.setOutput(song.pick.uri, null, OutputTarget.Mp3())

        viewModel.setBatchWide(OutputTarget.Aac(256))

        assertThat(output.resolve(song.pick.uri)).isEqualTo(OutputTarget.Mp3())
        assertThat(output.isManual(song.pick.uri)).isTrue()
        assertThat(output.resolve(file.pick.uri)).isEqualTo(OutputTarget.Aac(256))
        assertThat(output.isManual(file.pick.uri)).isFalse()
    }

    @Test
    fun `a song set by hand goes back to the batch-wide format`() {
        viewModel.addSources(listOf(song))
        viewModel.setOutput(song.pick.uri, null, OutputTarget.Mp3())

        viewModel.useInherited(song.pick.uri, null)

        assertThat(output.resolve(song.pick.uri)).isEqualTo(OutputTarget.Xdj700)
        assertThat(output.isManual(song.pick.uri)).isFalse()
        viewModel.setBatchWide(OutputTarget.Opus(160))
        assertThat(output.resolve(song.pick.uri)).isEqualTo(OutputTarget.Opus(160))
    }

    // Folders (S5)

    @Test
    fun `a folder's songs follow the folder's format unless one is set by hand`() {
        val wav24 = OutputTarget.Lossless(AudioFormat.WAV, 24)
        val vbr = OutputTarget.Mp3(Mp3Mode.Vbr(2))
        viewModel.addSources(listOf(djFolder))
        viewModel.setOutput(djFolder.pick.uri, null, wav24)
        viewModel.setOutput(djFolder.pick.uri, b.uri, vbr)

        viewModel.setBatchWide(OutputTarget.Opus(128))

        assertThat(output.resolve(djFolder.pick.uri, a.uri)).isEqualTo(wav24)
        assertThat(output.isManual(djFolder.pick.uri, a.uri)).isFalse()
        assertThat(output.resolve(djFolder.pick.uri, b.uri)).isEqualTo(vbr)
        assertThat(output.isManual(djFolder.pick.uri, b.uri)).isTrue()

        viewModel.useInherited(djFolder.pick.uri, b.uri)
        assertThat(output.resolve(djFolder.pick.uri, b.uri)).isEqualTo(wav24)
        assertThat(output.isManual(djFolder.pick.uri, b.uri)).isFalse()

        viewModel.useInherited(djFolder.pick.uri, null)
        assertThat(output.resolve(djFolder.pick.uri)).isEqualTo(OutputTarget.Opus(128))
        assertThat(output.resolve(djFolder.pick.uri, a.uri)).isEqualTo(OutputTarget.Opus(128))
        assertThat(output.isManual(djFolder.pick.uri)).isFalse()
    }

    @Test
    fun `opening a folder lists its songs once`() {
        viewModel.addSources(listOf(djFolder))

        viewModel.openFolder(djFolder.pick.uri)
        viewModel.openFolder(djFolder.pick.uri)

        val listed = state.folders[djFolder.pick.uri] as FolderContents.Listed
        assertThat(listed.files.map { it.uri }).containsExactly(a.uri, b.uri).inOrder()
        assertThat(listed.files.map { it.displayName }).containsExactly("a.flac", "b.wav").inOrder()
        assertThat(listed.skippedFolders).isEqualTo(0)
        assertThat(expander.calls).containsExactly(listOf(djFolder.pick))
    }

    @Test
    fun `a folder that cannot be listed says so and can be tried again`() {
        viewModel.addSources(listOf(djFolder))
        expander.failure = IOException("revoked")

        viewModel.openFolder(djFolder.pick.uri)
        assertThat(state.folders[djFolder.pick.uri]).isEqualTo(FolderContents.Failed)

        expander.failure = null
        viewModel.openFolder(djFolder.pick.uri)
        assertThat(state.folders[djFolder.pick.uri]).isInstanceOf(FolderContents.Listed::class.java)
    }

    @Test
    fun `a removed source forgets its formats and its listing`() {
        viewModel.addSources(listOf(djFolder))
        viewModel.setOutput(djFolder.pick.uri, null, OutputTarget.Mp3())
        viewModel.setOutput(djFolder.pick.uri, a.uri, OutputTarget.Aac())
        viewModel.openFolder(djFolder.pick.uri)

        viewModel.removeSource(djFolder)
        viewModel.addSources(listOf(djFolder))

        assertThat(output.isManual(djFolder.pick.uri)).isFalse()
        assertThat(output.isManual(djFolder.pick.uri, a.uri)).isFalse()
        assertThat(state.folders).doesNotContainKey(djFolder.pick.uri)
    }

    // Starting (S1)

    @Test
    fun `every file of the batch converts to its own format`() {
        viewModel.addSources(listOf(song, file, djFolder))
        viewModel.setBatchWide(OutputTarget.Aac(192))
        viewModel.setOutput(song.pick.uri, null, OutputTarget.Mp3())
        viewModel.setOutput(djFolder.pick.uri, null, OutputTarget.Lossless(AudioFormat.FLAC))
        viewModel.setOutput(djFolder.pick.uri, b.uri, OutputTarget.Opus(128))

        viewModel.convert()

        assertThat(lastBatchProfiles()).containsExactly(
            song.pick.uri, "out:mp3:cbr:320",
            file.pick.uri, "out:aac:192",
            a.uri, "out:flac:16:keep",
            b.uri, "out:opus:128",
        )
        assertThat(state.activeBatch?.batch?.profileId).isEqualTo("out:aac:192")
        // The choices for single songs last for their batch only; the batch-wide one stays.
        assertThat(output.isManual(song.pick.uri)).isFalse()
        assertThat(output.isManual(djFolder.pick.uri, b.uri)).isFalse()
        assertThat(output.batchWide).isEqualTo(OutputTarget.Aac(192))
    }

    // Lossy over lossless (S7)

    @Test
    fun `overwriting lossless originals with lossy files asks first, saying how many`() {
        viewModel.addSources(listOf(file, djFolder))
        viewModel.setBatchWide(OutputTarget.Mp3())
        // a.flac stays lossless; b.wav and intro.mp3 (already lossy) go to MP3.
        viewModel.setOutput(djFolder.pick.uri, a.uri, OutputTarget.Lossless(AudioFormat.FLAC))
        overwriteOriginals()

        viewModel.convert()

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.LossyOverwrite(1))
        assertThat(scheduler.conversions).isEmpty()
        assertThat(state.starting).isFalse()
        assertThat(state.sources).containsExactly(file, djFolder).inOrder()
    }

    @Test
    fun `confirming the lossy warning starts the batch as it was set up`() {
        viewModel.addSources(listOf(djFolder))
        viewModel.setBatchWide(OutputTarget.Opus(160))
        overwriteOriginals()
        viewModel.convert()
        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.LossyOverwrite(2))

        viewModel.confirmReplace()

        assertThat(viewModel.prompt.value).isNull()
        assertThat(scheduler.conversions).hasSize(1)
        assertThat(state.activeBatch?.batch?.overwriteOriginals).isTrue()
        assertThat(lastBatchProfiles()).containsExactly(a.uri, "out:opus:160", b.uri, "out:opus:160")
        assertThat(state.sources).isEmpty()
    }

    @Test
    fun `cancelling the lossy warning starts nothing and keeps the setup`() {
        viewModel.addSources(listOf(djFolder))
        viewModel.setBatchWide(OutputTarget.Aac())
        overwriteOriginals()
        viewModel.convert()

        viewModel.dismissReplacePrompt()

        assertThat(viewModel.prompt.value).isNull()
        assertThat(scheduler.conversions).isEmpty()
        assertThat(state.overwriteOriginals).isTrue()
        assertThat(state.sources).containsExactly(djFolder)
        assertThat(state.starting).isFalse()
        // Asked again on the next try.
        viewModel.convert()
        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.LossyOverwrite(2))
    }

    @Test
    fun `no lossy warning while originals are kept`() {
        viewModel.addSources(listOf(djFolder))
        viewModel.setBatchWide(OutputTarget.Mp3())

        viewModel.convert()

        assertThat(viewModel.prompt.value).isNull()
        assertThat(scheduler.conversions).hasSize(1)
    }

    @Test
    fun `no lossy warning when no lossless original would get a lossy file`() {
        viewModel.addSources(listOf(file, djFolder))
        viewModel.setBatchWide(OutputTarget.Lossless(AudioFormat.FLAC, 24))
        viewModel.setOutput(file.pick.uri, null, OutputTarget.Mp3())
        overwriteOriginals()

        viewModel.convert()

        assertThat(viewModel.prompt.value).isNull()
        assertThat(scheduler.conversions).hasSize(1)
        assertThat(state.activeBatch?.batch?.overwriteOriginals).isTrue()
    }

    @Test
    fun `a lossless file picked on its own is not counted, as its original cannot be replaced`() {
        viewModel.addSources(listOf(loose))
        viewModel.setBatchWide(OutputTarget.Mp3())
        overwriteOriginals()

        viewModel.convert()

        assertThat(viewModel.prompt.value).isNull()
        assertThat(scheduler.conversions).hasSize(1)
        assertThat(lastBatchProfiles()).containsExactly(loose.pick.uri, "out:mp3:cbr:320")
    }

    @Test
    fun `a folder and a lossless file picked on its own count only the folder's songs`() {
        viewModel.addSources(listOf(djFolder, loose))
        viewModel.setBatchWide(OutputTarget.Mp3())
        overwriteOriginals()

        viewModel.convert()

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.LossyOverwrite(2))
        assertThat(scheduler.conversions).isEmpty()
    }

    @Test
    fun `a library song counts only while its folder can be written`() {
        viewModel.addSources(listOf(song))
        viewModel.setBatchWide(OutputTarget.Opus(128))
        overwriteOriginals()
        viewModel.convert()
        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.LossyOverwrite(1))
        viewModel.dismissReplacePrompt()
        // Without write access the batch converts it like a file picked on its own, so nothing is replaced.
        access.denied = setOf("content://tree/lib")

        viewModel.convert()

        assertThat(viewModel.prompt.value).isNull()
        assertThat(scheduler.conversions).hasSize(1)
        assertThat(repository.itemsOf(scheduler.conversions.last()).single().sourceParentUri).isNull()
    }

    @Test
    fun `a library song counts by the codec the library read, not its extension`() {
        val alac = PendingSource(PickedSource.Track("content://tree/lib/document/alac.m4a"), "Alac", codec = "ALAC")
        val aac = PendingSource(PickedSource.Track("content://tree/lib/document/aac.m4a"), "Aac", codec = "AAC")
        expander.byPick[alac.pick.uri] = listOf(BatchSource(alac.pick.uri, "alac.m4a", "content://tree/lib"))
        expander.byPick[aac.pick.uri] = listOf(BatchSource(aac.pick.uri, "aac.m4a", "content://tree/lib"))
        viewModel.addSources(listOf(alac, aac))
        viewModel.setBatchWide(OutputTarget.Mp3())
        overwriteOriginals()

        viewModel.convert()

        assertThat(viewModel.prompt.value).isEqualTo(ReplacePrompt.LossyOverwrite(1))
        assertThat(scheduler.conversions).isEmpty()
    }

    @Test
    fun `a lossy check that cannot list the songs starts nothing`() {
        viewModel.addSources(listOf(djFolder))
        viewModel.setBatchWide(OutputTarget.Mp3())
        overwriteOriginals()
        expander.failure = IOException("revoked")

        viewModel.convert()

        assertThat(viewModel.prompt.value).isNull()
        assertThat(viewModel.notice.value).isEqualTo(ConverterNotice.START_FAILED)
        assertThat(scheduler.conversions).isEmpty()
        assertThat(state.starting).isFalse()
    }
}
