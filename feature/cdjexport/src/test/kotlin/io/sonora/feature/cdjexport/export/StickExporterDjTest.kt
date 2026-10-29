package io.sonora.feature.cdjexport.export

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.CueKind
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.export.AudioContainer
import io.sonora.core.domain.export.SourceAudio
import io.sonora.core.domain.export.SourceFile
import io.sonora.core.rekordbox.anlz.AnlzPaths
import io.sonora.core.rekordbox.anlz.AnlzTrack
import io.sonora.core.rekordbox.anlz.AnlzWriter
import io.sonora.core.rekordbox.manifest.ManifestJson
import io.sonora.core.rekordbox.manifest.StickManifest
import io.sonora.core.rekordbox.reference.AnlzReader
import io.sonora.core.rekordbox.reference.BeatGridTag
import io.sonora.core.rekordbox.reference.ColorDetailTag
import io.sonora.core.rekordbox.reference.CueListTag
import io.sonora.core.rekordbox.reference.ExtendedCueListTag
import io.sonora.core.rekordbox.reference.ThreeBandWaveformTag
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The export writes a track's DJ analysis into its analysis files when it has one, and exports as before when not. */
class StickExporterDjTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC)
    private val sources = FakeSources()
    private val stick = FakeStickFileSystem()
    private val dj = FakeDjExport()
    private val exporter = StickExporter(sources, FakeAnalyzer().cached(), clock, FakeConverter(sources), dj = dj)

    private val audio = "Contents/UnknownArtist/UnknownAlbum/a.aiff"
    private val anlz = AnlzPaths.forAudioPath("/$audio")

    private val provenance = AnalysisProvenance(1, BeatSource.QM_DSP, analysedAtEpochMs = 0)

    private fun analysis(edit: GridEdit? = null, waveform: BandWaveform = bands(), key: MusicalKey? = MusicalKey.F_MINOR) =
        DjAnalysis(
            durationMs = 10_000,
            grid = DjBeatGrid(listOf(TempoSegment(0.0, 100.0, 1))),
            key = key?.let { KeyEstimate(it, 0.8, "edma") },
            waveform = waveform,
            provenance = provenance,
            gridEdit = edit,
        )

    private fun bands(columns: Int = 1500) = BandWaveform(
        low = ByteArray(columns) { 200.toByte() }, mid = ByteArray(columns) { 100 }, high = ByteArray(columns) { 50 },
        peak = ByteArray(columns) { 255.toByte() }, version = 2,
    )

    private fun cue(id: String, kind: CueKind, slot: Int?, at: Double, name: String = "") =
        DjCue(id, kind, slot, at, null, CueColor.custom(0x28E214), name)

    private suspend fun export(file: SourceFile) = exporter.export(stick, ExportRequest(listOf(file), null))

    private fun manifest() = ManifestJson.decode(stick.bytes(StickManifest.PATH)!!)

    @Test
    fun `a track with a DJ analysis gets its edited grid, cues and colour waveform in its analysis files`() = runTest {
        val file = sources.add("a.aiff")
        dj.data[file.uri] = DjTrackExport(
            analysis(edit = GridEdit(12800, 40.0)),
            listOf(
                cue("hot-0", CueKind.HOT, 0, 1_000.0, name = "Intro"),
                cue("hot-4", CueKind.HOT, 4, 2_000.0),
                cue("m1", CueKind.MEMORY, null, 5_000.0),
            ),
        )

        export(file)

        val dat = AnlzReader.read(stick.bytes(anlz.datPath)!!)
        val beats = (dat.single("PQTZ") as BeatGridTag).beats
        assertThat(beats.take(3).map { it.tempo to it.timeMs }).containsExactly(12800 to 40L, 12800 to 509L, 12800 to 978L).inOrder()
        assertThat(beats.first().beatInBar).isEqualTo(1)
        val oldCues = dat.filter<CueListTag>()
        assertThat(oldCues[0].cues.map { it.hotCue }).containsExactly(1) // pad E (5) is past C
        assertThat(oldCues[1].cues.map { it.timeMs }).containsExactly(5_000L)

        val ext = AnlzReader.read(stick.bytes(anlz.extPath)!!)
        val extended = ext.filter<ExtendedCueListTag>()
        assertThat(extended[0].cues.map { it.hotCue }).containsExactly(1, 5).inOrder()
        assertThat(extended[0].cues.first().comment).isEqualTo("Intro")
        assertThat(extended[0].cues.first().red).isEqualTo(0x28)
        assertThat((ext.single("PWV5") as ColorDetailTag).entries.size).isEqualTo(1500)

        val ex2 = AnlzReader.read(stick.bytes(anlz.ex2Path)!!)
        assertThat(ex2.path).isEqualTo("/$audio")
        assertThat((ex2.single("PWV7") as ThreeBandWaveformTag).let { it.low(0) to it.high(0) }).isEqualTo(200 to 50)
    }

    @Test
    fun `the library lists the tempo of the grid in force and the DJ key`() = runTest {
        val file = sources.add("a.aiff")
        dj.data[file.uri] = DjTrackExport(analysis(edit = GridEdit(12850, 0.0)), emptyList())

        export(file)

        assertThat(manifest().tracks.single().bpm).isEqualTo(128.5)
        assertThat(manifest().tracks.single().key).isEqualTo(MusicalKey.F_MINOR)
    }

    @Test
    fun `the DJ data is looked up by the picked library track, also when it is converted first`() = runTest {
        val flac = sources.add(
            "b.flac", audio = SourceAudio(AudioContainer.FLAC, 24, 96_000, 2, durationMs = 10_000), uri = "content://library/b",
        )
        dj.data[flac.uri] = DjTrackExport(analysis(), listOf(cue("hot-0", CueKind.HOT, 0, 1_000.0)))

        export(flac)

        assertThat(dj.asked).containsExactly("content://library/b")
        val path = manifest().tracks.single().filePath
        val files = AnlzPaths.forAudioPath(path)
        val ext = AnlzReader.read(stick.bytes(files.extPath)!!)
        assertThat(ext.filter<ExtendedCueListTag>()[0].cues).hasSize(1)
        assertThat(stick.exists(files.ex2Path)).isTrue()
    }

    @Test
    fun `a track without DJ data is exported exactly as before`() = runTest {
        val file = sources.add("a.aiff")

        export(file)

        val old = AnlzTrack("/$audio", FakeAnalyzer.ANALYSIS.beatGrid, FakeAnalyzer.ANALYSIS.waveform)
        assertThat(stick.bytes(anlz.datPath)).isEqualTo(AnlzWriter.writeDat(old))
        assertThat(stick.bytes(anlz.extPath)).isEqualTo(AnlzWriter.writeExt(old))
        assertThat(stick.exists(anlz.ex2Path)).isFalse()
        assertThat(manifest().tracks.single().bpm).isEqualTo(128.0)
        assertThat(manifest().tracks.single().key).isEqualTo(MusicalKey.A_MINOR)
    }

    @Test
    fun `an analysis restored from a backup without its waveform still writes the grid and cues but no colour data`() = runTest {
        val file = sources.add("a.aiff")
        dj.data[file.uri] = DjTrackExport(
            analysis(waveform = BandWaveform.MISSING),
            listOf(cue("m1", CueKind.MEMORY, null, 3_000.0)),
        )

        export(file)

        val ext = AnlzReader.read(stick.bytes(anlz.extPath)!!)
        assertThat(ext.tags.map { it.fourCc }).doesNotContain("PWV5")
        assertThat(ext.filter<ExtendedCueListTag>()[1].cues.map { it.timeMs }).containsExactly(3_000L)
        assertThat(stick.exists(anlz.ex2Path)).isFalse()
        assertThat((AnlzReader.read(stick.bytes(anlz.datPath)!!).single("PQTZ") as BeatGridTag).beats.first().tempo).isEqualTo(10000)
    }

    @Test
    fun `a DJ source that fails never fails the track`() = runTest {
        val file = sources.add("a.aiff")
        dj.failure = IllegalStateException("store closed")

        val result = export(file) as ExportResult.Completed

        assertThat(result.failed).isEmpty()
        assertThat(result.exported).hasSize(1)
        assertThat(stick.exists(anlz.ex2Path)).isFalse()
    }

    @Test
    fun `a track whose export is undone leaves no 2EX file behind`() = runTest {
        val file = sources.add("a.aiff", changedBytes = ByteArray(2000) { 9 })
        dj.data[file.uri] = DjTrackExport(analysis(), emptyList())

        val result = export(file) as ExportResult.Completed

        assertThat(result.failed.single().reason).isEqualTo(TrackFailure.CHANGED_DURING_EXPORT)
        assertThat(stick.paths.filter { it.endsWith(".2EX") }).isEmpty()
    }

    // ---- a track exported before gets its DJ data when it is exported again ---------------------------------

    @Test
    fun `exporting a track again after editing its grid and cues updates its analysis files and the library tempo`() = runTest {
        val file = sources.add("a.aiff")
        dj.data[file.uri] = DjTrackExport(analysis(), emptyList())
        export(file)
        assertThat(manifest().tracks.single().bpm).isEqualTo(100.0)
        val audioWrites = stick.log.count { it == "write $audio" }
        dj.data[file.uri] = DjTrackExport(
            analysis(edit = GridEdit(12800, 40.0)),
            listOf(cue("hot-0", CueKind.HOT, 0, 1_000.0, name = "Drop")),
        )

        val result = export(file) as ExportResult.Completed

        assertThat(result.duplicates).hasSize(1)
        assertThat(result.libraryWritten).isTrue()
        assertThat(stick.log.count { it == "write $audio" }).isEqualTo(audioWrites) // the audio is not copied again
        val dat = AnlzReader.read(stick.bytes(anlz.datPath)!!)
        assertThat((dat.single("PQTZ") as BeatGridTag).beats.first().tempo).isEqualTo(12800)
        assertThat(dat.filter<CueListTag>()[0].cues.map { it.hotCue }).containsExactly(1)
        assertThat(AnlzReader.read(stick.bytes(anlz.extPath)!!).filter<ExtendedCueListTag>()[0].cues.single().comment)
            .isEqualTo("Drop")
        assertThat(manifest().tracks.single().bpm).isEqualTo(128.0)
        assertThat(stick.paths.none { it.endsWith(".sonora-new") }).isTrue()
    }

    @Test
    fun `exporting a track again with the same DJ data writes nothing`() = runTest {
        val file = sources.add("a.aiff")
        dj.data[file.uri] = DjTrackExport(analysis(), listOf(cue("hot-0", CueKind.HOT, 0, 1_000.0)))
        export(file)
        stick.log.clear()

        val result = export(file) as ExportResult.Completed

        assertThat(result.libraryWritten).isFalse()
        assertThat(stick.log).isEmpty()
    }

    @Test
    fun `a track exported without DJ data gains it when the DJ mode has analysed it since`() = runTest {
        val file = sources.add("a.aiff")
        export(file)
        assertThat(stick.exists(anlz.ex2Path)).isFalse()
        dj.data[file.uri] = DjTrackExport(analysis(), listOf(cue("m", CueKind.MEMORY, null, 2_000.0)))

        export(file)

        assertThat(stick.exists(anlz.ex2Path)).isTrue()
        assertThat(AnlzReader.read(stick.bytes(anlz.datPath)!!).filter<CueListTag>()[1].cues.map { it.timeMs }).containsExactly(2_000L)
    }

    @Test
    fun `a stick failure while updating keeps the old analysis files`() = runTest {
        val file = sources.add("a.aiff")
        dj.data[file.uri] = DjTrackExport(analysis(), emptyList())
        export(file)
        val before = stick.bytes(anlz.datPath)!!.toList()
        dj.data[file.uri] = DjTrackExport(analysis(edit = GridEdit(12800, 0.0)), emptyList())
        stick.failWrites = { it.endsWith("ANLZ0000.DAT.sonora-new") }

        val result = export(file)

        assertThat(result).isInstanceOf(ExportResult.Failed::class.java)
        assertThat(stick.bytes(anlz.datPath)!!.toList()).isEqualTo(before)
    }
}

/** DJ data by library uri, recording what was asked; [failure] makes every lookup throw. */
class FakeDjExport : DjExportSource {
    val data = mutableMapOf<String, DjTrackExport>()
    val asked = mutableListOf<String>()
    var failure: Exception? = null

    override suspend fun dataFor(sourceUri: String): DjTrackExport? {
        asked += sourceUri
        failure?.let { throw it }
        return data[sourceUri]
    }
}
