package io.sonora.feature.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.GridEditor
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

class GridEditCommandTest {

    /** 120 BPM, a bar start at 1300 ms: beats at 300 + 500 k. */
    private val analysed = DjBeatGrid(listOf(TempoSegment(1300.0, 120.0, 1)))
    private val editor = GridEditor(analysed, 60_000.0)

    @Test
    fun `set bar start here makes the beat nearest the playhead a bar start`() {
        val edit = GridEditCommand.SetBarStartHere.applyTo(editor, null, 2_290.0)

        assertThat(edit).isEqualTo(editor.setDownbeatAt(null, 2_290.0))
        assertThat(edit.anchorMs).isWithin(1e-6).of(2_300.0) // the phrase starts at the beat set
    }

    @Test
    fun `the beat buttons move the bar start by whole beats`() {
        val current = GridEdit(12_000, 300.0)

        assertThat(GridEditCommand.ShiftBeats(1).applyTo(editor, current, 0.0).anchorMs).isWithin(1e-6).of(800.0)
        assertThat(GridEditCommand.ShiftBeats(-1).applyTo(editor, current, 0.0).anchorMs).isWithin(1e-6).of(7_800.0)
    }

    @Test
    fun `the bar buttons move the phrase start by whole bars and leave the bar lines`() {
        val current = GridEdit(12_000, 300.0)

        val later = GridEditCommand.ShiftBars(1).applyTo(editor, current, 0.0)
        val earlier = GridEditCommand.ShiftBars(-1).applyTo(editor, current, 0.0)

        assertThat(later).isEqualTo(editor.shiftBars(current, 0.0, 1))
        assertThat(later.anchorMs).isWithin(1e-6).of(2_300.0)
        assertThat(earlier.anchorMs).isWithin(1e-6).of(6_300.0)
        assertThat(later.bpmHundredths).isEqualTo(12_000)
    }

    @Test
    fun `the nudge buttons move the grid by milliseconds`() {
        val current = GridEdit(12_000, 300.0)

        assertThat(GridEditCommand.Nudge(10.0).applyTo(editor, current, 0.0).anchorMs).isWithin(1e-6).of(310.0)
        assertThat(GridEditCommand.Nudge(-1.0).applyTo(editor, current, 0.0).anchorMs).isWithin(1e-6).of(299.0)
    }

    @Test
    fun `entering a tempo sets it through the editor and keeps the bar start`() {
        val current = GridEdit(12_800, 300.0)

        val edit = GridEditCommand.SetBpm(13_245).applyTo(editor, current, 0.0)

        assertThat(edit).isEqualTo(editor.setBpm(current, 0.0, 13_245))
        assertThat(edit.bpmHundredths).isEqualTo(13_245)
        assertThat(edit.anchorMs).isWithin(1e-6).of(300.0)
    }

    @Test
    fun `entering a tempo on an unedited track starts from the analysed grid`() {
        val edit = GridEditCommand.SetBpm(12_500).applyTo(editor, null, 0.0)

        assertThat(edit).isEqualTo(editor.setBpm(null, 0.0, 12_500))
        assertThat(edit.bpmHundredths).isEqualTo(12_500)
    }

    @Test
    fun `entering a tempo outside the range is limited to it like the step buttons`() {
        assertThat(GridEditCommand.SetBpm(1).applyTo(editor, GridEdit(12_000, 300.0), 0.0).bpmHundredths).isEqualTo(GridEdit.MIN_BPM_HUNDREDTHS)
        assertThat(GridEditCommand.SetBpm(99_999).applyTo(editor, GridEdit(12_000, 300.0), 0.0).bpmHundredths).isEqualTo(GridEdit.MAX_BPM_HUNDREDTHS)
    }

    @Test
    fun `the tempo buttons change the tempo and keep the bar start`() {
        val current = GridEdit(12_800, 300.0)

        assertThat(GridEditCommand.AdjustBpm(1).applyTo(editor, current, 0.0)).isEqualTo(GridEdit(12_801, 300.0))
        assertThat(GridEditCommand.AdjustBpm(-100).applyTo(editor, current, 0.0)).isEqualTo(GridEdit(12_700, 300.0))
        assertThat(GridEditCommand.HalveBpm.applyTo(editor, current, 0.0)).isEqualTo(GridEdit(6_400, 300.0))
        assertThat(GridEditCommand.DoubleBpm.applyTo(editor, current, 0.0)).isEqualTo(GridEdit(25_600, 300.0))
    }

    @Test
    fun `a command on an unedited track starts from the analysed grid`() {
        val edit = GridEditCommand.AdjustBpm(1).applyTo(editor, null, 0.0)

        assertThat(edit).isEqualTo(GridEdit(12_001, 1_300.0))
    }
}
