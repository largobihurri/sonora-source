package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class GridEditsTest {

    private val duration = 60_000.0

    /** 120 BPM, beats at 300 + 500 k, the beat at 1300 being a downbeat (so downbeats at 300 + 500 * (4 m - 2 ...)). */
    private val analysed = DjBeatGrid(listOf(TempoSegment(1300.0, 120.0, 1)))

    private fun editor(grid: DjBeatGrid? = analysed) = GridEditor(grid, duration)

    private fun downbeats(grid: DjBeatGrid?, upTo: Double = duration) =
        grid!!.beats(upTo).filter { it.beatInBar == 1 }.map { it.timeMs }

    /** The times of the first bar of each phrase, by the rule the waveform uses. */
    private fun phraseStartTimes(grid: DjBeatGrid, upTo: Double): List<Double> {
        val beats = grid.beats(upTo)
        val flags = grid.phraseStarts(beats)
        return beats.filterIndexed { i, _ -> flags[i] }.map { it.timeMs }
    }

    private fun assertAnchor(edit: GridEdit, expectedMs: Double) = assertThat(edit.anchorMs).isWithin(1e-6).of(expectedMs)

    // --- GridEdit

    @Test
    fun `an edit keeps its tempo as exact hundredths`() {
        val edit = GridEdit(12_800, 300.0)

        assertThat(edit.bpmHundredths).isEqualTo(12_800)
        assertThat(edit.bpm).isEqualTo(128.0)
        assertThat(edit.beatLengthMs).isWithin(1e-9).of(60_000.0 / 128.0)
        assertThat(GridEdit(12_801, 0.0).bpm).isEqualTo(128.01)
    }

    @Test
    fun `an edit refuses a tempo outside 20 to 400 BPM and an anchor that is not a number`() {
        assertThrows(IllegalArgumentException::class.java) { GridEdit(1_999, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GridEdit(40_001, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { GridEdit(12_000, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { GridEdit(12_000, Double.POSITIVE_INFINITY) }
        GridEdit(2_000, 0.0)
        GridEdit(40_000, 0.0)
    }

    // --- apply

    @Test
    fun `without an edit the analysed grid is the effective one`() {
        assertThat(GridEdits.apply(analysed, null)).isSameInstanceAs(analysed)
        assertThat(GridEdits.apply(null, null)).isNull()
    }

    @Test
    fun `an edit makes a constant grid at its tempo with a bar start every four beats from the anchor`() {
        val grid = GridEdits.apply(analysed, GridEdit(12_800, 1300.0))!!

        assertThat(grid.isConstant).isTrue()
        assertThat(grid.bpmAt(5_000.0)).isEqualTo(128.0)
        val beats = grid.beats(10_000.0)
        val beat = 60_000.0 / 128.0
        // The beat at 1300 is a beat 1 and so is every fourth one, both ways.
        assertThat(beats.single { it.timeMs == 1300.0 }.beatInBar).isEqualTo(1)
        assertThat(beats.map { it.timeMs }).isNotEmpty()
        assertThat(beats.first().timeMs).isAtLeast(0.0)
        assertThat(beats.first().timeMs).isLessThan(beat)
        for (b in beats) {
            val barsFromAnchor = (b.timeMs - 1300.0) / (4 * beat)
            assertThat(b.beatInBar == 1).isEqualTo(Math.abs(barsFromAnchor - Math.rint(barsFromAnchor)) < 1e-6)
        }
    }

    @Test
    fun `an edit reaches before its anchor down to the start of the track`() {
        val beats = GridEdits.apply(analysed, GridEdit(12_000, 1300.0))!!.beats(2_000.0)

        assertThat(beats.map { it.timeMs }).containsExactly(300.0, 800.0, 1300.0, 1800.0).inOrder()
        assertThat(beats.map { it.beatInBar }).containsExactly(3, 4, 1, 2).inOrder()
    }

    @Test
    fun `an anchor far into the track still gives the same grid over the whole track`() {
        val far = GridEdits.apply(analysed, GridEdit(12_000, 61_300.0))!!.beats(2_000.0)
        val near = GridEdits.apply(analysed, GridEdit(12_000, 1_300.0))!!.beats(2_000.0)

        assertThat(far.map { it.timeMs }).isEqualTo(near.map { it.timeMs })
        assertThat(far.map { it.beatInBar }).isEqualTo(near.map { it.beatInBar })
    }

    @Test
    fun `a negative anchor is a bar start before the track and the grid goes on from there`() {
        val beats = GridEdits.apply(analysed, GridEdit(12_000, -200.0))!!.beats(2_000.0)

        assertThat(beats.map { it.timeMs }).containsExactly(300.0, 800.0, 1300.0, 1800.0).inOrder()
        assertThat(beats.map { it.beatInBar }).containsExactly(2, 3, 4, 1).inOrder()
    }

    @Test
    fun `an edit gives a grid to a track whose analysis found no steady beat`() {
        val grid = GridEdits.apply(null, GridEdit(12_000, 250.0))!!

        assertThat(grid.beats(1_000.0).map { it.timeMs }).containsExactly(250.0, 750.0).inOrder()
        assertThat(grid.beats(1_000.0).first().beatInBar).isEqualTo(1)
    }

    @Test
    fun `the canonical anchor is the first phrase start of the track`() {
        // 120 BPM: a bar is 2000 ms and a phrase of four bars 8000 ms.
        assertThat(GridEdits.canonicalAnchorMs(300.0, 500.0)).isEqualTo(300.0)
        assertThat(GridEdits.canonicalAnchorMs(2_300.0, 500.0)).isWithin(1e-9).of(2_300.0)
        assertThat(GridEdits.canonicalAnchorMs(8_300.0, 500.0)).isWithin(1e-9).of(300.0)
        assertThat(GridEdits.canonicalAnchorMs(-200.0, 500.0)).isWithin(1e-9).of(7_800.0)
        assertThat(GridEdits.canonicalAnchorMs(8_000.0, 500.0)).isEqualTo(0.0)
        assertThat(GridEdits.canonicalAnchorMs(61_300.0, 500.0)).isWithin(1e-6).of(5_300.0)
    }

    @Test
    fun `an anchor stored when it was reduced to one bar is a phrase start in the first bar`() {
        // The D3.1 edits kept the anchor inside the first bar; it still reads, as the start of a phrase.
        val grid = GridEdits.apply(analysed, GridEdit(12_000, 300.0))!!

        assertThat(grid.beats(2_000.0).first { it.timeMs == 300.0 }.beatInBar).isEqualTo(1)
        assertThat(phraseStartTimes(grid, 20_000.0)).containsExactly(300.0, 8_300.0, 16_300.0).inOrder()
    }

    @Test
    fun `an edit says which bar of its phrase the first bar start of the track is`() {
        // 120 BPM, phrase start at 5300: the bars at 1300, 3300 and 5300 are bars 3, 4 and 1 of their phrases.
        val grid = GridEdits.apply(analysed, GridEdit(12_000, 5_300.0))!!

        assertThat(downbeats(grid, 6_000.0)).containsExactly(1_300.0, 3_300.0, 5_300.0).inOrder()
        assertThat(grid.segments.single().barInPhraseAtStart).isEqualTo(3)
        assertThat(phraseStartTimes(grid, 22_000.0)).containsExactly(5_300.0, 13_300.0, 21_300.0).inOrder()
        // The bar lines are those of an anchor anywhere in the phrase's bars.
        assertThat(downbeats(grid)).isEqualTo(downbeats(GridEdits.apply(analysed, GridEdit(12_000, 1_300.0)), duration))
    }

    @Test
    fun `an anchor far into the track keeps its place in the phrase`() {
        val far = GridEdits.apply(analysed, GridEdit(12_000, 5_300.0 + 8_000.0 * 7))!!
        val near = GridEdits.apply(analysed, GridEdit(12_000, 5_300.0))!!

        assertThat(phraseStartTimes(far, duration)).isEqualTo(phraseStartTimes(near, duration))
    }

    // --- setDownbeatAt

    @Test
    fun `setting the bar start snaps to the nearest beat of the analysed grid`() {
        // Beats at 300, 800, 1300, ... : 2290 is next to the beat at 2300.
        val edit = editor().setDownbeatAt(null, 2_290.0)

        assertThat(edit.bpmHundredths).isEqualTo(12_000)
        assertAnchor(edit, 2_300.0) // the phrase starts here
        assertThat(downbeats(GridEdits.apply(analysed, edit), 6_400.0)).containsExactly(300.0, 2_300.0, 4_300.0, 6_300.0).inOrder()
    }

    @Test
    fun `the beat set as bar start is the first bar of a phrase`() {
        val edit = editor().setDownbeatAt(null, 12_290.0) // the beat at 12300

        val grid = GridEdits.apply(analysed, edit)!!
        assertThat(phraseStartTimes(grid, 30_000.0)).containsExactly(4_300.0, 12_300.0, 20_300.0, 28_300.0).inOrder()
    }

    @Test
    fun `setting the bar start before the first beat takes the first beat`() {
        val edit = editor().setDownbeatAt(null, 100.0)

        assertAnchor(edit, 300.0)
        assertThat(downbeats(GridEdits.apply(analysed, edit), 3_000.0).first()).isEqualTo(300.0)
    }

    @Test
    fun `setting the bar start far into the track keeps the grid lines and only renumbers the bars`() {
        val edit = editor().setDownbeatAt(null, 30_810.0) // the beat at 30800

        val beats = GridEdits.apply(analysed, edit)!!.beats(duration)
        assertThat(beats.single { it.timeMs == 30_800.0 }.beatInBar).isEqualTo(1)
        // The beat times themselves are the analysed ones.
        assertThat(beats.map { it.timeMs }).isEqualTo(analysed.beats(duration).map { it.timeMs })
    }

    @Test
    fun `setting the bar start on an edited grid snaps to the edited beats, not the analysed ones`() {
        val existing = GridEdit(12_800, 100.0)
        val beat = 60_000.0 / 128.0

        val edit = editor().setDownbeatAt(existing, 10 * beat + 100.0 + 5.0)

        assertThat(edit.bpmHundredths).isEqualTo(12_800)
        assertAnchor(edit, GridEdits.canonicalAnchorMs(100.0 + 10 * beat, beat))
    }

    @Test
    fun `setting the bar start on a track without a grid puts it exactly where the playhead is`() {
        val edit = GridEditor(null, duration).setDownbeatAt(null, 5_250.0)

        assertThat(edit.bpmHundredths).isEqualTo(GridEdit.DEFAULT_BPM_HUNDREDTHS)
        assertAnchor(edit, 5_250.0) // inside the first 8000 ms phrase already
    }

    @Test
    fun `a first edit keeps the analysed tempo to the hundredth`() {
        val grid = DjBeatGrid(listOf(TempoSegment(300.0, 128.0037, 2)))

        val edit = GridEditor(grid, duration).setDownbeatAt(null, 10_000.0)

        assertThat(edit.bpmHundredths).isEqualTo(12_800)
    }

    @Test
    fun `on a grid whose tempo changes the edit takes the tempo and the beat at the playhead`() {
        val variable = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(2_000.0, 100.0, 1)))

        val edit = GridEditor(variable, 20_000.0).setDownbeatAt(null, 2_650.0) // 100 BPM: beats every 600 from 2000

        assertThat(edit.bpmHundredths).isEqualTo(10_000)
        assertAnchor(edit, GridEdits.canonicalAnchorMs(2_600.0, 600.0))
    }

    // --- shiftBeats

    @Test
    fun `shifting by a beat moves every bar start one beat later`() {
        val edit = editor().shiftBeats(GridEdit(12_000, 300.0), 0.0, 1)

        assertAnchor(edit, 800.0)
    }

    @Test
    fun `the phrase start wraps round the phrase, sixteen beats bring it back`() {
        var edit = GridEdit(12_000, 300.0)
        val seen = mutableListOf<Double>()
        repeat(16) {
            edit = editor().shiftBeats(edit, 0.0, 1)
            seen += edit.anchorMs
        }

        assertThat(seen[0]).isWithin(1e-6).of(800.0)
        assertThat(seen[2]).isWithin(1e-6).of(1_800.0)
        assertThat(seen[3]).isWithin(1e-6).of(2_300.0)
        assertThat(seen[14]).isWithin(1e-6).of(7_800.0)
        assertThat(seen[15]).isWithin(1e-6).of(300.0)
    }

    @Test
    fun `shifting earlier from the first phrase start wraps to the last beat of the first phrase`() {
        assertAnchor(editor().shiftBeats(GridEdit(12_000, 300.0), 0.0, -1), 7_800.0)
    }

    @Test
    fun `shifting by a beat moves the phrase with the bar lines`() {
        val before = GridEdits.apply(analysed, GridEdit(12_000, 1_300.0))!!
        val after = GridEdits.apply(analysed, editor().shiftBeats(GridEdit(12_000, 1_300.0), 0.0, 1))!!

        assertThat(downbeats(after, 6_000.0).first()).isEqualTo(downbeats(before, 6_000.0).first() + 500.0)
        assertThat(phraseStartTimes(after, 20_000.0)).containsExactly(1_800.0, 9_800.0, 17_800.0).inOrder()
    }

    // --- shiftBars

    @Test
    fun `shifting by a bar moves the phrase start four beats and leaves the bar lines`() {
        val edit = GridEdit(12_000, 300.0)
        val later = editor().shiftBars(edit, 0.0, 1)

        assertAnchor(later, 2_300.0)
        assertThat(downbeats(GridEdits.apply(analysed, later))).isEqualTo(downbeats(GridEdits.apply(analysed, edit)))
        assertThat(phraseStartTimes(GridEdits.apply(analysed, edit)!!, 20_000.0)).containsExactly(300.0, 8_300.0, 16_300.0).inOrder()
        assertThat(phraseStartTimes(GridEdits.apply(analysed, later)!!, 20_000.0)).containsExactly(2_300.0, 10_300.0, 18_300.0).inOrder()
    }

    @Test
    fun `four bar shifts bring the phrase back and one earlier wraps to the last bar`() {
        var edit = GridEdit(12_000, 300.0)
        repeat(4) { edit = editor().shiftBars(edit, 0.0, 1) }

        assertAnchor(edit, 300.0)
        assertAnchor(editor().shiftBars(GridEdit(12_000, 300.0), 0.0, -1), 6_300.0)
    }

    @Test
    fun `shifting an unedited track by a bar starts from the analysed phrase`() {
        val edit = editor().shiftBars(null, 0.0, 1)

        assertThat(edit.bpmHundredths).isEqualTo(12_000)
        assertAnchor(edit, 3_300.0) // the analysed first downbeat (1300) is a phrase start
    }

    @Test
    fun `a first edit that changes nothing keeps the analysed phrases`() {
        // The first downbeat of the track is at 300, so the analysed phrases start at 300 and 8300.
        val grid = DjBeatGrid(listOf(TempoSegment(2_300.0, 120.0, 1)))

        val edit = GridEditor(grid, duration).nudge(null, 0.0, 0.0)

        assertAnchor(edit, 300.0)
        assertThat(phraseStartTimes(GridEdits.apply(grid, edit)!!, 20_000.0)).isEqualTo(phraseStartTimes(grid, 20_000.0))
    }

    @Test
    fun `an analysed grid with an offset phrase keeps it on a first edit`() {
        val grid = DjBeatGrid(listOf(TempoSegment(300.0, 120.0, 3, barInPhraseAtStart = 2)))
        // Downbeats at 1300 (bar 2 of its phrase), 3300, 5300 and 7300 (bar 1).

        val edit = GridEditor(grid, duration).nudge(null, 0.0, 0.0)

        assertThat(phraseStartTimes(grid, 12_000.0)).containsExactly(7_300.0).inOrder()
        assertThat(phraseStartTimes(GridEdits.apply(grid, edit)!!, 12_000.0)).containsExactly(7_300.0).inOrder()
    }

    @Test
    fun `on a grid whose tempo changes a bar shift starts from the nearest phrase start`() {
        // 120 BPM for 16 beats (phrase starts at 0 and 8000), then 100 BPM (bars of 2400 ms: 8000, 10400, ..., 17600).
        val variable = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(8_000.0, 100.0, 1)))

        val edit = GridEditor(variable, 30_000.0).shiftBars(null, 15_500.0, 0)

        assertThat(edit.bpmHundredths).isEqualTo(10_000)
        assertAnchor(edit, 8_000.0) // 17600 (the nearest phrase start) folded into the first 9600 ms phrase
    }

    @Test
    fun `shifting an unedited track starts from the analysed bar start`() {
        val edit = editor().shiftBeats(null, 0.0, 1)

        assertThat(edit.bpmHundredths).isEqualTo(12_000)
        assertAnchor(edit, 1_800.0) // the analysed downbeat is at 1300
    }

    // --- nudge

    @Test
    fun `nudging moves the grid by the milliseconds asked`() {
        assertAnchor(editor().nudge(GridEdit(12_000, 300.0), 0.0, 10.0), 310.0)
        assertAnchor(editor().nudge(GridEdit(12_000, 300.0), 0.0, -1.0), 299.0)
    }

    @Test
    fun `nudging past the start of the phrase wraps`() {
        assertAnchor(editor().nudge(GridEdit(12_000, 5.0), 0.0, -10.0), 7_995.0)
        assertAnchor(editor().nudge(GridEdit(12_000, 7_995.0), 0.0, 10.0), 5.0)
    }

    @Test
    fun `ten nudges of a tenth of a millisecond equal one millisecond`() {
        var edit = GridEdit(12_000, 300.0)
        repeat(10) { edit = editor().nudge(edit, 0.0, 0.1) }

        assertThat(edit.anchorMs).isEqualTo(301.0)
    }

    @Test
    fun `nudging an unedited track starts from the analysed grid`() {
        val edit = editor().nudge(null, 0.0, 10.0)

        assertAnchor(edit, 1_310.0)
    }

    // --- tempo

    @Test
    fun `setting the tempo keeps the first bar start`() {
        val edit = editor().setBpm(GridEdit(12_000, 300.0), 0.0, 12_800)

        assertThat(edit.bpmHundredths).isEqualTo(12_800)
        assertAnchor(edit, 300.0)
    }

    @Test
    fun `a faster tempo that makes the phrase shorter than the anchor folds the anchor into the first phrase`() {
        // 240 BPM: a phrase is 4000 ms, so 7500 is the phrase start 3500.
        val edit = editor().setBpm(GridEdit(12_000, 7_500.0), 0.0, 24_000)

        assertThat(edit.bpmHundredths).isEqualTo(24_000)
        assertAnchor(edit, 3_500.0)
    }

    @Test
    fun `the tempo goes up and down by hundredths`() {
        assertThat(editor().adjustBpm(GridEdit(12_800, 300.0), 0.0, 1).bpmHundredths).isEqualTo(12_801)
        assertThat(editor().adjustBpm(GridEdit(12_800, 300.0), 0.0, -1).bpmHundredths).isEqualTo(12_799)
        assertThat(editor().adjustBpm(GridEdit(12_800, 300.0), 0.0, 100).bpmHundredths).isEqualTo(12_900)
        assertThat(editor().adjustBpm(GridEdit(12_800, 300.0), 0.0, -100).bpmHundredths).isEqualTo(12_700)
    }

    @Test
    fun `the tempo of an unedited track starts from the analysed one`() {
        val edit = editor().adjustBpm(null, 0.0, 1)

        assertThat(edit.bpmHundredths).isEqualTo(12_001)
        assertAnchor(edit, 1_300.0)
    }

    @Test
    fun `the tempo stays inside 20 to 400 BPM`() {
        assertThat(editor().setBpm(GridEdit(12_000, 0.0), 0.0, 100).bpmHundredths).isEqualTo(GridEdit.MIN_BPM_HUNDREDTHS)
        assertThat(editor().setBpm(GridEdit(12_000, 0.0), 0.0, 99_999).bpmHundredths).isEqualTo(GridEdit.MAX_BPM_HUNDREDTHS)
        assertThat(editor().adjustBpm(GridEdit(2_000, 0.0), 0.0, -1).bpmHundredths).isEqualTo(GridEdit.MIN_BPM_HUNDREDTHS)
        assertThat(editor().adjustBpm(GridEdit(40_000, 0.0), 0.0, 100).bpmHundredths).isEqualTo(GridEdit.MAX_BPM_HUNDREDTHS)
    }

    @Test
    fun `halving and doubling keep the first bar start`() {
        val halved = editor().halve(GridEdit(17_450, 300.0), 0.0)
        val doubled = editor().double(GridEdit(8_725, 300.0), 0.0)

        assertThat(halved.bpmHundredths).isEqualTo(8_725)
        assertThat(doubled.bpmHundredths).isEqualTo(17_450)
        assertAnchor(halved, 300.0)
        assertAnchor(doubled, 300.0)
    }

    @Test
    fun `halving an odd number of hundredths rounds to the nearest`() {
        assertThat(editor().halve(GridEdit(12_801, 0.0), 0.0).bpmHundredths).isEqualTo(6_401)
    }

    @Test
    fun `halving and doubling an unedited track work from the analysed tempo`() {
        assertThat(editor().halve(null, 0.0).bpmHundredths).isEqualTo(6_000)
        assertThat(editor().double(null, 0.0).bpmHundredths).isEqualTo(24_000)
    }

    @Test
    fun `halving and doubling are refused at the limits and the tempo is clamped`() {
        assertThat(GridEdits.canHalve(4_000)).isTrue()
        assertThat(GridEdits.canHalve(3_998)).isFalse()
        assertThat(GridEdits.canDouble(20_000)).isTrue()
        assertThat(GridEdits.canDouble(20_001)).isFalse()
        assertThat(editor().halve(GridEdit(2_500, 0.0), 0.0).bpmHundredths).isEqualTo(GridEdit.MIN_BPM_HUNDREDTHS)
        assertThat(editor().double(GridEdit(30_000, 0.0), 0.0).bpmHundredths).isEqualTo(GridEdit.MAX_BPM_HUNDREDTHS)
    }

    @Test
    fun `the effective grid of the editor is the edited one`() {
        val edit = GridEdit(12_800, 300.0)

        assertThat(editor().effective(null)).isSameInstanceAs(analysed)
        assertThat(editor().effective(edit)).isEqualTo(GridEdits.apply(analysed, edit))
    }
}
