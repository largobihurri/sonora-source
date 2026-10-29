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
import io.sonora.core.rekordbox.anlz.AnlzBeat
import io.sonora.core.rekordbox.anlz.AnlzCue
import io.sonora.core.rekordbox.anlz.LoopSize
import io.sonora.core.rekordbox.pdb.DeviceFileType
import org.junit.Test

/** What Sonora's DJ analysis, grid edit and cues become in a track's ANLZ files. */
class DjAnlzMapperTest {

    private val base = FakeAnalyzer.ANALYSIS
    private val aiff = StickAudio(DeviceFileType.AIFF, encoderDelayFrames = 0, sampleRateHz = 44_100)
    private val provenance = AnalysisProvenance(1, BeatSource.QM_DSP, analysedAtEpochMs = 0)

    private fun grid(bpm: Double = 120.0, startMs: Double = 100.0, beatInBar: Int = 1) =
        DjBeatGrid(listOf(TempoSegment(startMs, bpm, beatInBar)))

    private fun analysis(
        grid: DjBeatGrid? = grid(),
        edit: GridEdit? = null,
        waveform: BandWaveform = BandWaveform.MISSING,
        durationMs: Long = 2_100,
        key: MusicalKey? = null,
    ) = DjAnalysis(durationMs, grid, key?.let { KeyEstimate(it, 0.9, "edma") }, waveform, provenance, edit)

    private fun cue(
        id: String,
        kind: CueKind,
        slot: Int?,
        at: Double,
        loopEnd: Double? = null,
        rgb: Int = 0x28E214,
        name: String = "",
    ) = DjCue(id, kind, slot, at, loopEnd, CueColor.custom(rgb), name)

    private fun map(dj: DjTrackExport?, target: StickAudio = aiff) = DjAnlzMapper.anlzTrack("/Contents/a.aiff", base, dj, target)

    // ---- the grid ---------------------------------------------------------------------------------------------

    @Test
    fun `PQTZ takes the beats of the analysed grid with bar numbers from the grid and tempo times 100`() {
        val track = map(DjTrackExport(analysis(grid(bpm = 120.0, startMs = 100.0, beatInBar = 3)), emptyList()))

        assertThat(track.beats!!.take(4)).containsExactly(
            AnlzBeat(3, 12000, 100), AnlzBeat(4, 12000, 600), AnlzBeat(1, 12000, 1100), AnlzBeat(2, 12000, 1600),
        ).inOrder()
        assertThat(track.beats!!).hasSize(4) // the beat at 2100 ms is the end of the track
    }

    @Test
    fun `the beats stop at the end of the track`() {
        val track = map(DjTrackExport(analysis(durationMs = 1_600), emptyList()))

        assertThat(track.beats!!.map { it.timeMs }).containsExactly(100L, 600L, 1100L).inOrder()
    }

    @Test
    fun `the user's grid edit wins over the analysed grid`() {
        val edited = analysis(grid = grid(bpm = 100.0, startMs = 0.0), edit = GridEdit(12800, 40.0), durationMs = 1_000)

        val beats = map(DjTrackExport(edited, emptyList())).beats!!

        assertThat(beats.map { it.tempo }.distinct()).containsExactly(12800)
        assertThat(beats.map { it.timeMs }).containsExactly(40L, 509L, 978L).inOrder() // 468.75 ms a beat
        assertThat(beats.map { it.beatInBar }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `a tempo change gives every beat its own tempo`() {
        val changing = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(1_000.0, 150.0, 3)))

        val beats = map(DjTrackExport(analysis(grid = changing, durationMs = 1_900), emptyList())).beats!!

        assertThat(beats).containsExactly(
            AnlzBeat(1, 12000, 0), AnlzBeat(2, 12000, 500), AnlzBeat(3, 15000, 1000),
            AnlzBeat(4, 15000, 1400), AnlzBeat(1, 15000, 1800),
        ).inOrder()
    }

    @Test
    fun `an MP3 grid is moved by the encoder delay and the decoder delay into rekordbox's time base`() {
        val mp3 = StickAudio(DeviceFileType.MP3, encoderDelayFrames = 576, sampleRateHz = 44_100)

        val beats = map(DjTrackExport(analysis(durationMs = 1_200), emptyList()), mp3).beats!!

        // (576 + 529) frames at 44.1 kHz = 25.06 ms.
        assertThat(beats.map { it.timeMs }).containsExactly(125L, 625L, 1125L).inOrder()
    }

    @Test
    fun `an AAC grid is moved by the priming only and a WAV, AIFF or FLAC one by nothing`() {
        val m4a = StickAudio(DeviceFileType.M4A, encoderDelayFrames = 2_112, sampleRateHz = 44_100)
        val dj = DjTrackExport(analysis(durationMs = 700), emptyList())

        assertThat(map(dj, m4a).beats!!.first().timeMs).isEqualTo(148L) // 100 + 47.9
        for (type in listOf(DeviceFileType.AIFF, DeviceFileType.WAV, DeviceFileType.FLAC)) {
            assertThat(map(dj, StickAudio(type, 0, 48_000)).beats!!.first().timeMs).isEqualTo(100L)
        }
    }

    @Test
    fun `a track whose analysis found no grid keeps the quarter-tone grid of the old analyser`() {
        val track = map(DjTrackExport(analysis(grid = null), emptyList()))

        assertThat(track.beats).isNull()
        assertThat(track.beatGrid).isEqualTo(base.beatGrid)
    }

    @Test
    fun `without DJ data the track is exactly the old one`() {
        val track = map(null)

        assertThat(track.beatGrid).isEqualTo(base.beatGrid)
        assertThat(track.waveform).isEqualTo(base.waveform)
        assertThat(track.beats).isNull()
        assertThat(track.cues).isEmpty()
        assertThat(track.bands).isNull()
        assertThat(track.filePath).isEqualTo("/Contents/a.aiff")
    }

    // ---- the cues -----------------------------------------------------------------------------------------

    @Test
    fun `hot cue pads A to H are hot cues 1 to 8 and memory cues are 0`() {
        val cues = (0..7).map { cue("hot-$it", CueKind.HOT, it, 1_000.0 * (it + 1)) } +
            cue("m1", CueKind.MEMORY, null, 500.0)

        val mapped = map(DjTrackExport(analysis(), cues)).cues

        assertThat(mapped.filter { it.hotCue != 0 }.map { it.hotCue }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
        assertThat(mapped.single { it.hotCue == 0 }.timeMs).isEqualTo(500)
    }

    @Test
    fun `cue times are rounded to the millisecond and move with the time base`() {
        val mp3 = StickAudio(DeviceFileType.MP3, encoderDelayFrames = 576, sampleRateHz = 44_100)
        val cues = listOf(cue("hot-0", CueKind.HOT, 0, 1_000.4), cue("hot-1", CueKind.HOT, 1, 2_000.5, loopEnd = 3_000.2))

        val none = map(DjTrackExport(analysis(), cues)).cues
        val shifted = map(DjTrackExport(analysis(), cues), mp3).cues

        assertThat(none.map { it.timeMs }).containsExactly(1_000L, 2_001L).inOrder()
        assertThat(none[1].loopEndMs).isEqualTo(3_000)
        assertThat(shifted.map { it.timeMs }).containsExactly(1_025L, 2_026L).inOrder() // + 25.06 ms
        assertThat(shifted[1].loopEndMs).isEqualTo(3_025)
    }

    @Test
    fun `colour and name pass to the cue and a long name is cut`() {
        val cues = listOf(
            cue("hot-0", CueKind.HOT, 0, 100.0, rgb = 0xFF3B30, name = "Drop"),
            cue("m", CueKind.MEMORY, null, 200.0, rgb = 0x305AFF, name = "x".repeat(64)),
        )

        val mapped = map(DjTrackExport(analysis(), cues)).cues

        assertThat(mapped[0]).isEqualTo(AnlzCue(1, 100, rgb = 0xFF3B30, comment = "Drop"))
        assertThat(mapped[1].rgb).isEqualTo(0x305AFF)
        assertThat(mapped[1].comment).hasLength(64)
    }

    @Test
    fun `a loop on the grid carries its length in beats`() {
        // 120 BPM: 500 ms a beat. 4 beats = 2000 ms, half a beat = 250 ms, 3 beats is no loop size rekordbox has.
        val cues = listOf(
            cue("hot-0", CueKind.HOT, 0, 1_000.0, loopEnd = 3_000.0),
            cue("hot-1", CueKind.HOT, 1, 4_000.0, loopEnd = 4_250.0),
            cue("hot-2", CueKind.HOT, 2, 6_000.0, loopEnd = 7_500.0),
            cue("hot-3", CueKind.HOT, 3, 9_000.0, loopEnd = 9_321.0),
        )

        val sizes = map(DjTrackExport(analysis(durationMs = 20_000), cues)).cues.map { it.loopSize }

        assertThat(sizes).containsExactly(LoopSize(4, 1), LoopSize(1, 2), null, null).inOrder()
    }

    // ---- the colour waveform --------------------------------------------------------------------------------

    @Test
    fun `the 3-band waveform feeds the colour waveforms and a missing one adds nothing`() {
        val waveform = BandWaveform(
            low = byteArrayOf(10, 20, 30), mid = byteArrayOf(1, 2, 3), high = byteArrayOf(4, 5, 6), peak = byteArrayOf(7, 8, 9), version = 2,
        )

        val track = map(DjTrackExport(analysis(waveform = waveform), emptyList()))

        assertThat(track.bands!!.columnCount).isEqualTo(3)
        assertThat(map(DjTrackExport(analysis(waveform = BandWaveform.MISSING), emptyList())).bands).isNull()
    }

    // ---- the library fields ------------------------------------------------------------------------------

    @Test
    fun `the library tempo is the tempo of the first beat, edits included, and the key is the DJ key`() {
        val dj = DjTrackExport(analysis(grid = grid(bpm = 100.0), edit = GridEdit(12850, 0.0), key = MusicalKey.F_MINOR), emptyList())

        assertThat(DjAnlzMapper.tempoBpm(base, dj)).isEqualTo(128.5)
        assertThat(DjAnlzMapper.key(base, dj)).isEqualTo(MusicalKey.F_MINOR)
        assertThat(DjAnlzMapper.tempoBpm(base, null)).isEqualTo(128.0)
        assertThat(DjAnlzMapper.key(base, null)).isEqualTo(base.key)
        assertThat(DjAnlzMapper.tempoBpm(base, DjTrackExport(analysis(grid = null), emptyList()))).isEqualTo(128.0)
        assertThat(DjAnlzMapper.key(base, DjTrackExport(analysis(key = null), emptyList()))).isEqualTo(base.key)
    }
}
