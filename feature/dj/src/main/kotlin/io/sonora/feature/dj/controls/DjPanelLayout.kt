package io.sonora.feature.dj.controls

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.djmodel.DjCue

/**
 * The widths of the cells of the pad panel's rows (D4.2c), kept as numbers so the narrowest phone can be checked in a unit
 * test: the panel is 288 dp wide on a 320 dp phone (16 dp margins), and the panel's two faces share their second row's first
 * cells, the pad mode switch and Q. The composables take their widths and weights from here.
 */
object DjPanelLayout {

    /** The `Cues | Loop` switch: two segments of 40 dp. */
    val ModeSwitchWidth: Dp = 80.dp

    /** The Q pill. */
    val QuantizeWidth: Dp = 40.dp

    /** The ‹ and › arrows of the beat-length stepper (and, in the cue face, the previous and next memory cue buttons' minimum). */
    val StepperArrowWidth: Dp = 34.dp

    /** The loop transport row: In, Out, Exit / Reloop, 1/2X and 2X; Exit / Reloop has the longest word and the most room. */
    val TransportWeights: List<Float> = listOf(1f, 1f, 1.5f, 1f, 1f)

    /** The cue face's second row after the switch and Q: previous memory cue, "+ Memory", next memory cue. */
    val CueRowWeights: List<Float> = listOf(1f, 2.4f, 1f)

    /** The widths of the five transport buttons in a row [content] wide with [gap] between them. */
    fun transportWidths(content: Dp, gap: Dp): List<Dp> = shares(content - gap * (TransportWeights.size - 1), TransportWeights)

    /** The widths of previous, "+ Memory" and next in the cue face's second row, after the switch and Q and four gaps. */
    fun cueRowWidths(content: Dp, gap: Dp): List<Dp> =
        shares(content - ModeSwitchWidth - QuantizeWidth - gap * 4, CueRowWeights)

    /** The width of the stepper's value cell in the loop face's second row: what the switch, Q, two arrows and four gaps leave. */
    fun stepperValueWidth(content: Dp, gap: Dp): Dp = content - ModeSwitchWidth - QuantizeWidth - StepperArrowWidth * 2 - gap * 4

    /** The narrowest hot cue pad the one-row deck strip ([DjDeckRow]) accepts; below it the two-row pad panel is used. */
    val DeckPadMinWidth: Dp = 36.dp

    /** The width of each of the eight pads of [DjDeckRow] in a row [content] wide: after the switch and eight gaps. */
    fun deckRowPadWidth(content: Dp, gap: Dp): Dp = (content - ModeSwitchWidth - gap * DjCue.HOT_SLOTS) / DjCue.HOT_SLOTS

    /** Whether [DjDeckRow] fits [content]: each of its pads keeps at least [DeckPadMinWidth]. */
    fun deckRowFits(content: Dp, gap: Dp): Boolean = deckRowPadWidth(content, gap) >= DeckPadMinWidth

    private fun shares(total: Dp, weights: List<Float>): List<Dp> {
        val sum = weights.sum()
        return weights.map { total * (it / sum) }
    }
}
