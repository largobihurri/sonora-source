package io.sonora.feature.cdjexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.StickIoException
import io.sonora.core.domain.export.StickVolume
import io.sonora.feature.cdjexport.export.DuplicateTrack
import io.sonora.feature.cdjexport.export.ExportJob
import io.sonora.feature.cdjexport.export.ExportPhase
import io.sonora.feature.cdjexport.export.ExportProgress
import io.sonora.feature.cdjexport.export.ExportResult
import io.sonora.feature.cdjexport.export.ExportedTrack
import io.sonora.feature.cdjexport.export.FailedTrack
import io.sonora.feature.cdjexport.export.FakeAnalyzer
import io.sonora.feature.cdjexport.export.FakeConverter
import io.sonora.feature.cdjexport.export.FakeSources
import io.sonora.feature.cdjexport.export.FakeStickFileSystem
import io.sonora.feature.cdjexport.export.FakeUriPermissions
import io.sonora.feature.cdjexport.export.ExportConversion
import io.sonora.feature.cdjexport.export.StickExporter
import io.sonora.feature.cdjexport.export.StickLibrary
import io.sonora.feature.cdjexport.export.TrackFailure
import io.sonora.feature.cdjexport.export.cached
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

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class ExportToStickViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC)
    private val sources = FakeSources()
    private val stickFs = FakeStickFileSystem(free = 8L shl 30)
    private val locator = FakeLocator()
    private val runner = FakeRunner()
    private val permissions = FakeUriPermissions()
    private val requests = ExportRequests()
    private val volumes = FakeRemovableVolumes()
    private lateinit var viewModel: ExportToStickViewModel

    private val state get() = viewModel.state.value
    private val en = XmlStrings.of()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        locator.volumes[STICK] = StickVolume("SONORA", isRemovableRoot = true, fileSystem = stickFs)
        viewModel = ExportToStickViewModel(
            sources,
            locator,
            StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources)),
            runner,
            permissions,
            dispatcher,
            clock,
            ENVIRONMENT,
            requests,
            volumes,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun pickGoodTracks(vararg names: String) =
        viewModel.onFilesPicked(names.map { sources.add(it).uri })

    private fun readyToExport() {
        pickGoodTracks("a.aiff", "b.aiff")
        viewModel.onStickPicked(STICK)
    }

    @Test
    fun `picked files the player cannot take are listed to be converted, the unreadable ones rejected with why`() {
        val good = sources.add("good.aiff")
        val flac = sources.add("song.flac", audio = SourceAudio(AudioContainer.FLAC, 16, 44_100, 2))
        val deep = sources.add("deep.aiff", audio = SourceAudio(AudioContainer.AIFF, 24, 44_100, 2))
        val fast = sources.add("fast.aiff", audio = SourceAudio(AudioContainer.AIFF, 16, 96_000, 2))
        val adpcm = sources.add("adpcm.aiff", audio = SourceAudio(AudioContainer.AIFF_C, compression = "ima4"))
        val gone = sources.add("gone.aiff")
        sources.unreadable += gone.uri

        viewModel.onFilesPicked(listOf(good.uri, flac.uri, deep.uri, fast.uri, adpcm.uri, gone.uri))

        assertThat(state.tracks.map { it.name to it.converts }).containsExactly(
            "good.aiff" to false,
            "song.flac" to true,
            "deep.aiff" to true,
            "fast.aiff" to true,
        ).inOrder()
        assertThat(state.convertCount).isEqualTo(3)
        assertThat(trackDetail(state.tracks.first().file, en)).isEqualTo("AIFF 16-bit · 44.1 kHz · 1.0 kB")
        assertThat(state.rejected.map { it.name to it.reason.message(state.profile, en) }).containsExactly(
            "adpcm.aiff" to "Sonora can't decode this audio (AIFF-C), so it can't convert it for the XDJ-700.",
            "gone.aiff" to "Sonora can't read this file. It may have been moved or deleted.",
        ).inOrder()
        assertThat(state.inspecting).isFalse()
        assertThat(permissions.read).containsAtLeast(good.uri, flac.uri)
    }

    @Test
    fun `the room an export needs counts the tracks to convert at their converted size`() {
        val flac = sources.add("song.flac", audio = SourceAudio(AudioContainer.FLAC, 24, 96_000, 2, durationMs = 60_000))

        viewModel.onFilesPicked(listOf(flac.uri))

        val converted = ExportConversion.exportBytes(flac, state.profile)
        assertThat(converted).isGreaterThan(flac.sizeBytes)
        assertThat(state.totalBytes).isEqualTo(flac.sizeBytes)
        assertThat(state.neededBytes).isEqualTo(StickExporter.requiredBytes(converted, 1, 0))
    }

    @Test
    fun `a file picked twice is listed once`() {
        val one = sources.add("a.aiff")

        viewModel.onFilesPicked(listOf(one.uri))
        viewModel.onFilesPicked(listOf(one.uri))

        assertThat(state.tracks).hasSize(1)
    }

    @Test
    fun `a picked folder adds the audio files in it`() {
        val a = sources.add("a.aiff")
        val b = sources.add("b.mp3", audio = SourceAudio(AudioContainer.MP3))
        sources.folders["content://tree/music"] = listOf(a.uri, b.uri)

        viewModel.onFolderPicked("content://tree/music")

        assertThat(state.tracks.map { it.name to it.converts }).containsExactly("a.aiff" to false, "b.mp3" to true)
        assertThat(state.rejected).isEmpty()
        assertThat(permissions.read).contains("content://tree/music")
    }

    @Test
    fun `tracks can be removed or cleared`() {
        pickGoodTracks("a.aiff", "b.aiff")

        viewModel.removeTrack(state.tracks.first().uri)
        assertThat(state.tracks.map { it.name }).containsExactly("b.aiff")

        viewModel.clearTracks()
        assertThat(state.tracks).isEmpty()
        assertThat(state.rejected).isEmpty()
        assertThat(permissions.released).isNotEmpty()
    }

    @Test
    fun `a picked stick shows its name, free space and library`() {
        viewModel.onStickPicked(STICK)

        val stick = state.stick!!
        assertThat(stick.name).isEqualTo("SONORA")
        assertThat(stick.freeBytes).isEqualTo(8L shl 30)
        assertThat(stick.library).isEqualTo(StickLibrary.Empty)
        assertThat(stick.problem).isNull()
        assertThat(permissions.readWrite).containsExactly(STICK)
    }

    @Test
    fun `a folder or the phone's storage is not accepted as the stick`() {
        locator.volumes["content://tree/primary"] = StickVolume("Internal", isRemovableRoot = false, fileSystem = stickFs)

        viewModel.onStickPicked("content://tree/primary")

        assertThat(state.stick!!.problem).isEqualTo(StickProblem.NOT_A_STICK)
        pickGoodTracks("a.aiff")
        assertThat(state.canExport).isFalse()
    }

    @Test
    fun `a stick that cannot be opened says so`() {
        viewModel.onStickPicked("content://tree/gone")

        assertThat(state.stick!!.problem).isEqualTo(StickProblem.UNUSABLE)
        assertThat(state.stick!!.problem!!.message(en)).contains("can't be used")
        assertThat(state.canExport).isFalse()
    }

    @Test
    fun `exporting needs tracks, a usable stick and enough room`() {
        assertThat(state.canExport).isFalse()
        pickGoodTracks("a.aiff")
        assertThat(state.canExport).isFalse()
        viewModel.onStickPicked(STICK)
        assertThat(state.canExport).isTrue()

        stickFs.free = 1_000
        viewModel.onStickPicked(STICK)

        assertThat(state.lacksSpace).isTrue()
        assertThat(state.canExport).isFalse()
    }

    @Test
    fun `starting an export hands the tracks and a dated playlist to the runner`() {
        readyToExport()
        assertThat(state.playlistName).isEqualTo("Sonora 2026-10-03")

        viewModel.startExport()

        val (volume, request) = runner.started.single()
        assertThat(volume.name).isEqualTo("SONORA")
        assertThat(request.tracks.map { it.fileName }).containsExactly("a.aiff", "b.aiff").inOrder()
        assertThat(request.playlistName).isEqualTo("Sonora 2026-10-03")
        assertThat(request.replaceUnknownLibrary).isFalse()
    }

    @Test
    fun `a blank playlist name exports without a playlist`() {
        readyToExport()
        viewModel.onPlaylistNameChange("   ")

        viewModel.startExport()

        assertThat(runner.started.single().second.playlistName).isNull()
    }

    @Test
    fun `a library Sonora did not make is only replaced after confirmation`() {
        stickFs.put(StickExporter.PDB_PATH, byteArrayOf(1))
        readyToExport()

        viewModel.startExport()
        assertThat(state.step).isEqualTo(ExportStep.ConfirmReplace(StickLibrary.Foreign))
        assertThat(runner.started).isEmpty()

        viewModel.dismissReplace()
        assertThat(state.step).isEqualTo(ExportStep.Setup)

        viewModel.startExport()
        viewModel.confirmReplace()
        assertThat(runner.started.single().second.replaceUnknownLibrary).isTrue()
    }

    @Test
    fun `the running export shows its progress and can be cancelled`() {
        readyToExport()
        viewModel.startExport()
        val progress = ExportProgress(1, 2, "b.aiff", ExportPhase.COPYING, 0.5f)

        runner.job.value = ExportJob.Running("SONORA", 2, progress)

        val running = state.step as ExportStep.Running
        assertThat(running.progress).isEqualTo(progress)
        assertThat(exportProgressText(running, en)).isEqualTo("Copying 2 of 2: b.aiff")
        viewModel.cancelExport()
        assertThat(runner.cancelled).isTrue()
    }

    @Test
    fun `a finished export shows its result until the user is done`() {
        readyToExport()
        viewModel.startExport()
        val result = ExportResult.Completed(
            exported = listOf(ExportedTrack("a.aiff", "/Contents/UnknownArtist/UnknownAlbum/a.aiff")),
            duplicates = listOf(DuplicateTrack("b.aiff", "/Contents/UnknownArtist/UnknownAlbum/b.aiff")),
            failed = listOf(FailedTrack("c.aiff", TrackFailure.NOT_ANALYSABLE, FakeAnalyzer.NO_BEATS)),
            playlist = "Sonora 2026-10-03",
            libraryTrackCount = 5,
            libraryWritten = true,
        )

        runner.job.value = ExportJob.Finished("SONORA", result)

        val done = state.step as ExportStep.Done
        val summary = summarize(done.result, en)
        assertThat(summary.title).isEqualTo("Export finished with problems")
        assertThat(summary.message).startsWith("Added 1 track to the playlist \"Sonora 2026-10-03\".")
        assertThat(summary.skipped).containsExactly("b.aiff: already on the stick")
        assertThat(summary.failed).containsExactly("c.aiff: no tempo or beat grid was found")
        assertThat(done.technicalDetails).contains("NoBeatsDetected")

        viewModel.finish()

        assertThat(runner.acknowledged).isTrue()
        assertThat(state.step).isEqualTo(ExportStep.Setup)
        assertThat(state.tracks).isEmpty()
        // The runner took over the tracks' access with the request; the screen gives up nothing twice.
        assertThat(runner.started.single().second.grants).containsExactly("content://picked/a.aiff", "content://picked/b.aiff")
        assertThat(permissions.released).isEmpty()
    }

    @Test
    fun `a failed export offers its technical details`() {
        readyToExport()
        viewModel.startExport()

        runner.job.value = ExportJob.Finished("SONORA", ExportResult.Failed("Copying a.aiff", StickIoException("gone")))

        val done = state.step as ExportStep.Done
        assertThat(summarize(done.result, en).isError).isTrue()
        assertThat(done.technicalDetails).contains("StickIoException: gone")
    }

    @Test
    fun `an export finished while the screen was away is shown when it comes back`() {
        runner.job.value = ExportJob.Finished("SONORA", ExportResult.Cancelled)

        val again = newViewModel()

        assertThat(summarize((again.state.value.step as ExportStep.Done).result, en).title).isEqualTo("Export cancelled")
    }

    @Test
    fun `a stick whose access cannot be kept says so, and can be picked again`() {
        permissions.readWriteAnswer = { false }
        viewModel.onStickPicked(STICK)

        assertThat(state.stick!!.problem).isEqualTo(StickProblem.NO_ACCESS)
        assertThat(state.openingStick).isFalse()

        permissions.readWriteAnswer = { throw SecurityException("No persistable grant") }
        viewModel.onStickPicked(STICK)

        assertThat(state.stick!!.problem).isEqualTo(StickProblem.NO_ACCESS)
        assertThat(state.openingStick).isFalse()
        pickGoodTracks("a.aiff")
        assertThat(state.canExport).isFalse()

        permissions.readWriteAnswer = { true }
        viewModel.onStickPicked(STICK)

        assertThat(state.stick!!.problem).isNull()
        assertThat(state.canExport).isTrue()
    }

    @Test
    fun `an export refused because another one runs says so and keeps the tracks' access`() {
        readyToExport()
        runner.refuse = true

        viewModel.startExport()

        assertThat(state.notice).isEqualTo(ExportNotice.ANOTHER_EXPORT_RUNNING)
        assertThat(state.step).isEqualTo(ExportStep.Setup)
        assertThat(permissions.kept).containsExactly("content://picked/a.aiff", "content://picked/b.aiff")

        runner.refuse = false
        viewModel.startExport()

        assertThat(state.notice).isNull()
        assertThat(runner.started.single().second.grants).containsExactly("content://picked/a.aiff", "content://picked/b.aiff")
    }

    @Test
    fun `a folder that cannot be listed is named in the rejected list`() {
        val folder = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FHouse"

        viewModel.onFolderPicked(folder)

        assertThat(state.rejected).containsExactly(RejectedTrack(folder, "House", RejectReason.UnreadableFolder))
        assertThat(state.inspecting).isFalse()
        assertThat(permissions.kept).isEmpty()
    }

    @Test
    fun `a file whose access cannot be kept is still looked at on its own`() {
        val a = sources.add("a.aiff")
        val b = sources.add("b.aiff")
        val gone = sources.add("gone.aiff")
        sources.unreadable += gone.uri
        permissions.readFailure = { if (it == b.uri) IllegalStateException("Provider died") else null }

        viewModel.onFilesPicked(listOf(a.uri, b.uri, gone.uri))

        assertThat(state.tracks.map { it.name }).containsExactly("a.aiff", "b.aiff").inOrder()
        assertThat(state.rejected).containsExactly(RejectedTrack(gone.uri, "gone.aiff", RejectReason.UnreadableFile))
    }

    @Test
    fun `access is kept only for listed tracks`() {
        val good = sources.add("good.aiff")
        val flac = sources.add("song.flac", audio = SourceAudio(AudioContainer.FLAC, 16, 44_100, 2))
        val adpcm = sources.add("adpcm.aiff", audio = SourceAudio(AudioContainer.AIFF_C, compression = "ima4"))

        viewModel.onFilesPicked(listOf(good.uri, flac.uri, adpcm.uri))

        assertThat(permissions.kept).containsExactly(good.uri, flac.uri)

        val a = sources.add("a.aiff")
        sources.folders["content://tree/music"] = listOf(a.uri)
        viewModel.onFolderPicked("content://tree/music")
        assertThat(permissions.kept).containsExactly(good.uri, flac.uri, "content://tree/music")

        viewModel.removeTrack(a.uri)
        assertThat(permissions.kept).containsExactly(good.uri, flac.uri)
    }

    @Test
    fun `leaving the screen gives up the access it kept, but not what a running export took`() {
        val store = ViewModelStore()
        own(viewModel, store)
        pickGoodTracks("a.aiff")

        store.clear()

        assertThat(permissions.kept).isEmpty()

        val exporting = newViewModel()
        val running = ViewModelStore()
        own(exporting, running)
        exporting.onFilesPicked(listOf(sources.add("b.aiff").uri))
        exporting.onStickPicked(STICK)
        exporting.startExport()

        running.clear()

        assertThat(permissions.kept).containsExactly("content://picked/b.aiff")
        assertThat(runner.started.single().second.grants).containsExactly("content://picked/b.aiff")
    }

    @Test
    fun `a request from another screen replaces the selection with one playlist named after it`() {
        pickGoodTracks("old.aiff")
        viewModel.onStickPicked(STICK)
        val a = sources.add("a.aiff", uri = libraryUri("a.aiff"))
        val b = sources.add("b.aiff", uri = libraryUri("b.aiff"))

        requests.request("Afrikan Simba", listOf(b.uri, a.uri))

        assertThat(state.tracks.map { it.name }).containsExactly("b.aiff", "a.aiff").inOrder()
        assertThat(state.playlistName).isEqualTo("Afrikan Simba")
        assertThat(requests.pending.value).isNull()
        // The user still confirms the export.
        assertThat(runner.started).isEmpty()

        viewModel.startExport()

        val request = runner.started.single().second
        assertThat(request.tracks.map { it.uri }).containsExactly(b.uri, a.uri).inOrder()
        assertThat(request.playlistName).isEqualTo("Afrikan Simba")
    }

    @Test
    fun `a request is applied once, also when the screen is made again`() {
        val a = sources.add("a.aiff", uri = libraryUri("a.aiff"))
        requests.request("Album", listOf(a.uri))
        assertThat(state.tracks).hasSize(1)

        viewModel.clearTracks()
        val again = newViewModel()

        assertThat(state.tracks).isEmpty()
        assertThat(again.state.value.tracks).isEmpty()
        assertThat(again.state.value.playlistName).isEqualTo("Sonora 2026-10-03")
    }

    @Test
    fun `a request made before the screen opens is applied when it does`() {
        // Requests no screen watches yet (the one from setUp watches its own).
        val waiting = ExportRequests()
        val a = sources.add("a.aiff", uri = libraryUri("a.aiff"))
        waiting.request("Album", listOf(a.uri))

        val opened = ExportToStickViewModel(
            sources, locator, StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources)), runner, permissions, dispatcher, clock,
            ENVIRONMENT, waiting, volumes,
        )

        assertThat(opened.state.value.tracks.map { it.name }).containsExactly("a.aiff")
        assertThat(opened.state.value.playlistName).isEqualTo("Album")
        assertThat(waiting.pending.value).isNull()
    }

    @Test
    fun `tracks of a request that are missing are listed as rejected, those that do not fit as tracks to convert`() {
        val good = sources.add("good.aiff", uri = libraryUri("good.aiff"))
        val flac = sources.add(
            "song.flac",
            audio = SourceAudio(AudioContainer.FLAC, 16, 44_100, 2),
            uri = libraryUri("song.flac"),
        )
        val gone = libraryUri("gone.aiff")

        requests.request("Album", listOf(good.uri, flac.uri, gone))

        assertThat(state.tracks.map { it.name }).containsExactly("good.aiff", "song.flac").inOrder()
        assertThat(state.rejected.map { it.name to it.reason }).containsExactly("gone.aiff" to RejectReason.UnreadableFile)
        assertThat(state.inspecting).isFalse()
        assertThat(requests.pending.value).isNull()
    }

    @Test
    fun `a request made during an export waits until the user is done with the result`() {
        readyToExport()
        viewModel.startExport()
        val a = sources.add("a.aiff", uri = libraryUri("a.aiff"))

        requests.request("Album", listOf(a.uri))

        assertThat(requests.pending.value).isNotNull()
        assertThat(state.playlistName).isEqualTo("Sonora 2026-10-03")

        runner.job.value = ExportJob.Finished("SONORA", ExportResult.Cancelled)
        assertThat(requests.pending.value).isNotNull()

        viewModel.finish()

        assertThat(requests.pending.value).isNull()
        assertThat(state.step).isEqualTo(ExportStep.Setup)
        assertThat(state.tracks.map { it.uri }).containsExactly(a.uri)
        assertThat(state.playlistName).isEqualTo("Album")
    }

    @Test
    fun `a mounted stick Sonora already has access to opens by itself`() {
        permissions.heldTrees += STICK

        volumes.current.value = listOf(volume("1234-ABCD", "SONORA"))

        assertThat(state.stick!!.name).isEqualTo("SONORA")
        assertThat(state.stick!!.problem).isNull()
        assertThat(state.attached.map { it.name }).containsExactly("SONORA")
        assertThat(state.stickSize).isEqualTo(16L shl 30)
    }

    @Test
    fun `a mounted stick without access waits for the user, and its picker opens at it`() {
        volumes.current.value = listOf(volume("1234-ABCD", "SONORA"))

        assertThat(state.stick).isNull()
        assertThat(viewModel.chooseStick()).isEqualTo(StickPick.PickerAt("1234-ABCD"))

        viewModel.onStickPicked(STICK)

        assertThat(state.stick!!.name).isEqualTo("SONORA")
    }

    @Test
    fun `choosing the only mounted stick with access opens it with no picker`() {
        volumes.current.value = listOf(volume("1234-ABCD", "SONORA"))
        viewModel.onStickPicked("content://tree/gone")
        permissions.heldTrees += STICK
        volumes.current.value = listOf(volume("1234-ABCD", "SONORA", totalBytes = 8L shl 30))

        assertThat(viewModel.chooseStick()).isEqualTo(StickPick.Opened)
        assertThat(state.stick!!.treeUri).isEqualTo(STICK)
        assertThat(state.stick!!.problem).isNull()
    }

    @Test
    fun `with several mounted the user chooses one, opened directly when Sonora has access to it`() {
        val other = "content://com.android.externalstorage.documents/tree/9999-0000%3A"
        locator.volumes[other] = StickVolume("CARD", isRemovableRoot = true, fileSystem = FakeStickFileSystem())
        permissions.heldTrees += STICK
        volumes.current.value = listOf(volume("9999-0000", "CARD"), volume("1234-ABCD", "SONORA"))

        assertThat(state.stick).isNull()
        assertThat(viewModel.chooseStick()).isEqualTo(StickPick.Picker(null))
        assertThat(viewModel.chooseVolume("9999-0000")).isEqualTo(StickPick.PickerAt("9999-0000"))
        assertThat(viewModel.chooseVolume("1234-ABCD")).isEqualTo(StickPick.Opened)
        assertThat(state.stick!!.name).isEqualTo("SONORA")
    }

    @Test
    fun `access that is gone sends the next choice to the picker`() {
        permissions.heldTrees += STICK
        permissions.readWriteAnswer = { false }

        volumes.current.value = listOf(volume("1234-ABCD", "SONORA"))

        assertThat(state.stick!!.problem).isEqualTo(StickProblem.NO_ACCESS)
        assertThat(viewModel.chooseStick()).isEqualTo(StickPick.PickerAt("1234-ABCD"))
    }

    @Test
    fun `a stick opens by itself only while none is chosen and tracks are being chosen`() {
        val other = "content://com.android.externalstorage.documents/tree/9999-0000%3A"
        locator.volumes[other] = StickVolume("OTHER", isRemovableRoot = true, fileSystem = FakeStickFileSystem())
        viewModel.onStickPicked(other)
        permissions.heldTrees += STICK

        volumes.current.value = listOf(volume("1234-ABCD", "SONORA"))

        assertThat(state.stick!!.name).isEqualTo("OTHER")
    }

    @Test
    fun `ejecting lets go of the stick, and it does not come back by itself until it is plugged in again`() {
        permissions.heldTrees += STICK
        volumes.current.value = listOf(volume("1234-ABCD", "SONORA"))
        pickGoodTracks("a.aiff")
        viewModel.startExport()
        runner.job.value = ExportJob.Finished("SONORA", ExportResult.Cancelled)

        viewModel.forgetStick()

        assertThat(state.stick).isNull()
        viewModel.finish()
        assertThat(state.step).isEqualTo(ExportStep.Setup)
        assertThat(state.stick).isNull()

        volumes.current.value = listOf(volume("1234-ABCD", "SONORA", totalBytes = 1))
        assertThat(state.stick).isNull()

        volumes.current.value = listOf(volume("1234-ABCD", "SONORA", RemovableVolume.State.UNMOUNTED))
        volumes.current.value = listOf(volume("1234-ABCD", "SONORA"))
        assertThat(state.stick!!.name).isEqualTo("SONORA")
    }

    private fun volume(
        uuid: String,
        name: String,
        state: RemovableVolume.State = RemovableVolume.State.MOUNTED,
        totalBytes: Long = 16L shl 30,
    ) = RemovableVolume(uuid, name, totalBytes, state)

    private fun libraryUri(name: String) =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F$name"

    private fun newViewModel() = ExportToStickViewModel(
        sources, locator, StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources)), runner, permissions, dispatcher, clock, ENVIRONMENT,
        requests, volumes,
    )

    /** Puts [model] in [store], so clearing the store clears it as leaving the screen would. */
    private fun own(model: ExportToStickViewModel, store: ViewModelStore) {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
        }
        ViewModelProvider(store, factory)[ExportToStickViewModel::class.java]
    }

    private companion object {
        const val STICK = "content://com.android.externalstorage.documents/tree/1234-ABCD%3A"
        val ENVIRONMENT = AppEnvironment("0.1.0 (1)", "16", 36, "Google Pixel 9")
    }
}
