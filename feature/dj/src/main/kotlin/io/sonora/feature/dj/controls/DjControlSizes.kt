package io.sonora.feature.dj.controls

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The sizes of the DJ control rows, so the portrait layout can budget its height (D4.1b): the rows are fixed heights,
 * never "whatever the content measures", which is what lets the screen promise its scrolling waveform a minimum height.
 *
 * [control] is the height of a row of touch targets: the hot cue pads, the readout and its reset pill, the tempo fader
 * and the BPM nudges, and each row of the grid editor. [slim] is the height of the rows of text pills: Q with the memory
 * cue buttons, and the tempo range with Master Tempo. [gap] separates rows. Everything is dark and translucent
 * over the artwork.
 */
@Immutable
data class DjControlSizes(val control: Dp, val slim: Dp, val gap: Dp) {

    /** The cue panel: the pad row, a gap and the Q / memory row. */
    val cuePanel: Dp get() = control + gap + slim

    /**
     * The loop panel (the other face of the cue panel's place, see [DjPadMode]): the loop transport row, a gap and the row with
     * the pad mode switch, Q and the beat-length stepper. The same rows as the cue panel, so the height never changes.
     */
    val loopPanel: Dp get() = control + gap + slim

    /** The height the pad panel is given, whichever [DjPadMode] it shows: the taller of the two faces. */
    val modePanel: Dp get() = maxOf(cuePanel, loopPanel)

    /** The tempo section: the readout row, the fader row and the range row, with two gaps. */
    val tempoSection: Dp get() = control + gap + control + gap + slim

    /**
     * The tablet's tempo column (landscape, a vertical fader beside it): the readout row, the row of the two nudges and the
     * reset pill, the range and Master Tempo, stacked with three gaps. The column must be at least this tall.
     */
    val tempoColumn: Dp get() = control + gap + control + gap + slim + gap + slim

    /** The grid editor: [GRID_EDITOR_ROWS] rows of [control] height and the gaps between them. */
    val gridEditor: Dp get() = control * GRID_EDITOR_ROWS + gap * (GRID_EDITOR_ROWS - 1)

    companion object {
        /** Rows of the grid editor: title and Done, BPM, halve / double / reset, beat, bar and the millisecond nudges. */
        const val GRID_EDITOR_ROWS = 6

        /** Room to spare (a tall phone): 44 dp rows, the sizes the screen had before the cue panel. */
        val Comfortable = DjControlSizes(control = 44.dp, slim = 44.dp, gap = 6.dp)

        /** A phone about 700 dp tall: 40 dp touch rows, 34 dp pill rows and 4 dp gaps. */
        val Compact = DjControlSizes(control = 40.dp, slim = 34.dp, gap = 4.dp)

        /** A tablet (T6): 56 dp touch rows and 48 dp pill rows, comfortable for fingers on a big screen. */
        val Tablet = DjControlSizes(control = 56.dp, slim = 48.dp, gap = 8.dp)
    }
}
