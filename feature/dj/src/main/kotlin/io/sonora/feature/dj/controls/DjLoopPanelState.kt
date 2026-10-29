package io.sonora.feature.dj.controls

import androidx.compose.runtime.Immutable
import io.sonora.core.domain.playback.DjLoopState
import io.sonora.feature.dj.waveform.LoopRegion

/** What the Exit / Reloop button of a CDJ does now: leave the loop that plays, or start the remembered one again. */
enum class LoopExitMode { EXIT, RELOOP }

/** Which controls the pad panel shows: the eight hot cue pads with Q and the memory cues, or the loop controls. */
enum class DjPadMode { CUES, LOOP }

/**
 * What the loop buttons look like and can do, worked out from the controller's [DjLoopState] (pure: the rules are
 * tested without a screen). Nothing is enabled while loops are [available] (not with bit-perfect output armed, DJ mode off
 * or a stream the loop engine cannot host); beat loops also need a beat grid.
 *
 * @property inLit Loop In was pressed and Out has not been: the In button is lit.
 * @property outLit a loop is set and plays (or waits for the audio to reach its Out): the Out button is lit.
 * @property armed the loop waits for its Out (lit more softly than a loop that plays).
 * @property beats the size the stepper shows: the loop's own size while it is one of the sizes, else the one chosen.
 * @property beatsLit the stepper is lit while a loop plays.
 * @property canSave the loop that plays has its times on the grid, so it can become a hot loop or a memory loop.
 * @property region the loop to draw on the waveforms, `null` without an In.
 */
@Immutable
data class DjLoopPanelState(
    val available: Boolean,
    val inEnabled: Boolean,
    val inLit: Boolean,
    val outEnabled: Boolean,
    val outLit: Boolean,
    val armed: Boolean,
    val exitMode: LoopExitMode,
    val exitReloopEnabled: Boolean,
    val resizeEnabled: Boolean,
    val beatsEnabled: Boolean,
    val beatsLit: Boolean,
    val beats: Double,
    val canStepUp: Boolean,
    val canStepDown: Boolean,
    val canSave: Boolean,
    val region: LoopRegion?,
) {
    /** A loop plays or waits for its Out. */
    val engaged: Boolean get() = outLit

    companion object {
        /** Loops unavailable, nothing set, four beats chosen. */
        val OFF: DjLoopPanelState = of(DjLoopState.NONE, hasGrid = false, chosenBeats = BeatLengthStepper.DEFAULT, measuredBeats = null)

        fun of(state: DjLoopState, hasGrid: Boolean, chosenBeats: Double, measuredBeats: Double?): DjLoopPanelState {
            val on = state.available
            val engaged = on && (state.looping || state.armed)
            val beats = BeatLengthStepper.shown(chosenBeats, measuredBeats.takeIf { state.outMs != null })
            val beatsEnabled = on && hasGrid
            return DjLoopPanelState(
                available = on,
                inEnabled = on,
                inLit = on && state.markingIn,
                outEnabled = on && state.markingIn,
                outLit = engaged,
                armed = on && state.armed,
                exitMode = if (engaged) LoopExitMode.EXIT else LoopExitMode.RELOOP,
                exitReloopEnabled = on && (engaged || state.canReloop),
                resizeEnabled = on && state.inMs != null && state.outMs != null,
                beatsEnabled = beatsEnabled,
                beatsLit = engaged,
                beats = beats,
                canStepUp = beatsEnabled && BeatLengthStepper.canStep(beats, +1),
                canStepDown = beatsEnabled && BeatLengthStepper.canStep(beats, -1),
                canSave = engaged && state.gridInMs != null && state.gridOutMs != null,
                region = LoopRegion.of(state),
            )
        }
    }
}
