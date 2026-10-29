package io.sonora.feature.dj

import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.GridEditor

/** One thing the beat grid editor's buttons do; [applyTo] is the whole of its effect on the edit. */
sealed interface GridEditCommand {

    /** The edit this command makes from [edit] (`null` while the track is unedited), with the playhead at [positionMs]. */
    fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit

    /** "Set bar start here": the beat nearest the playhead becomes the first beat of a bar. */
    data object SetBarStartHere : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit =
            editor.setDownbeatAt(edit, positionMs)
    }

    /** Moves the bar starts, and the phrase with them, by [beats] whole beats (negative: earlier). */
    data class ShiftBeats(val beats: Int) : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit =
            editor.shiftBeats(edit, positionMs, beats)
    }

    /** Moves the phrase start by [bars] whole bars (negative: earlier), leaving every bar line where it is. */
    data class ShiftBars(val bars: Int) : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit =
            editor.shiftBars(edit, positionMs, bars)
    }

    /** Moves the whole grid by [deltaMs]. */
    data class Nudge(val deltaMs: Double) : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit =
            editor.nudge(edit, positionMs, deltaMs)
    }

    /** Sets the tempo to [bpmHundredths] hundredths of a BPM (entered or tapped), limited to the range of an edit. */
    data class SetBpm(val bpmHundredths: Int) : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit =
            editor.setBpm(edit, positionMs, bpmHundredths)
    }

    /** Changes the tempo by [deltaHundredths] hundredths of a BPM. */
    data class AdjustBpm(val deltaHundredths: Int) : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit =
            editor.adjustBpm(edit, positionMs, deltaHundredths)
    }

    /** Halves the tempo. */
    data object HalveBpm : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit = editor.halve(edit, positionMs)
    }

    /** Doubles the tempo. */
    data object DoubleBpm : GridEditCommand {
        override fun applyTo(editor: GridEditor, edit: GridEdit?, positionMs: Double): GridEdit = editor.double(edit, positionMs)
    }
}
