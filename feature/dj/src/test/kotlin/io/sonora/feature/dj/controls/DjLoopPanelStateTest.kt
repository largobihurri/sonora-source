package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.DjLoopState
import org.junit.Test

/** What each loop button looks like and can do, from the controller's state: pure, so the rules are tested without a screen. */
class DjLoopPanelStateTest {

    private fun panel(state: DjLoopState, hasGrid: Boolean = true, chosen: Double = 4.0, measured: Double? = null) =
        DjLoopPanelState.of(state, hasGrid, chosen, measured)

    private val idle = DjLoopState(available = true)
    private val marking = DjLoopState(inMs = 1_000.0, gridInMs = 1_000.0, available = true)
    private val armed = DjLoopState(1_000.0, 3_000.0, armed = true, available = true, gridInMs = 1_000.0, gridOutMs = 3_000.0)
    private val looping = DjLoopState(1_000.0, 3_000.0, looping = true, available = true, gridInMs = 1_000.0, gridOutMs = 3_000.0)
    private val exited = DjLoopState(1_000.0, 3_000.0, available = true, gridInMs = 1_000.0, gridOutMs = 3_000.0)

    @Test
    fun `with loops unavailable every button is off`() {
        val p = panel(DjLoopState(available = false), hasGrid = true)

        assertThat(p.inEnabled).isFalse()
        assertThat(p.outEnabled).isFalse()
        assertThat(p.exitReloopEnabled).isFalse()
        assertThat(p.resizeEnabled).isFalse()
        assertThat(p.beatsEnabled).isFalse()
        assertThat(p.canSave).isFalse()
        assertThat(p.available).isFalse()
    }

    @Test
    fun `an idle player can mark an In, and has nothing to close, leave or resize`() {
        val p = panel(idle)

        assertThat(p.inEnabled).isTrue()
        assertThat(p.inLit).isFalse()
        assertThat(p.outEnabled).isFalse()
        assertThat(p.exitMode).isEqualTo(LoopExitMode.RELOOP)
        assertThat(p.exitReloopEnabled).isFalse()
        assertThat(p.resizeEnabled).isFalse()
        assertThat(p.canSave).isFalse()
    }

    @Test
    fun `after Loop In the In button is lit and Out can close the loop`() {
        val p = panel(marking)

        assertThat(p.inLit).isTrue()
        assertThat(p.outEnabled).isTrue()
        assertThat(p.outLit).isFalse()
        assertThat(p.exitReloopEnabled).isFalse()
    }

    @Test
    fun `while looping Out is lit, the toggle offers Exit, the loop can be resized and saved`() {
        val p = panel(looping)

        assertThat(p.outLit).isTrue()
        assertThat(p.inLit).isFalse()
        assertThat(p.exitMode).isEqualTo(LoopExitMode.EXIT)
        assertThat(p.exitReloopEnabled).isTrue()
        assertThat(p.resizeEnabled).isTrue()
        assertThat(p.canSave).isTrue()
    }

    @Test
    fun `a loop waiting for its Out is lit, can be left and saved`() {
        val p = panel(armed)

        assertThat(p.outLit).isTrue()
        assertThat(p.armed).isTrue()
        assertThat(p.exitMode).isEqualTo(LoopExitMode.EXIT)
        assertThat(p.canSave).isTrue()
    }

    @Test
    fun `after Exit the toggle offers Reloop and the loop is neither lit nor saveable`() {
        val p = panel(exited)

        assertThat(p.outLit).isFalse()
        assertThat(p.exitMode).isEqualTo(LoopExitMode.RELOOP)
        assertThat(p.exitReloopEnabled).isTrue()
        assertThat(p.resizeEnabled).isTrue()
        assertThat(p.canSave).isFalse()
    }

    @Test
    fun `a loop whose grid times are unknown cannot be saved`() {
        val seeking = looping.copy(gridInMs = null, gridOutMs = null)

        assertThat(panel(seeking).canSave).isFalse()
    }

    @Test
    fun `beat loops need a grid`() {
        assertThat(panel(idle, hasGrid = true).beatsEnabled).isTrue()
        assertThat(panel(idle, hasGrid = false).beatsEnabled).isFalse()
    }

    @Test
    fun `the stepper shows the measured length of the loop, else the chosen one, and lights with the loop`() {
        val p = panel(looping, chosen = 8.0, measured = 2.0)
        assertThat(p.beats).isEqualTo(2.0)
        assertThat(p.beatsLit).isTrue()

        val q = panel(idle, chosen = 8.0)
        assertThat(q.beats).isEqualTo(8.0)
        assertThat(q.beatsLit).isFalse()
    }

    @Test
    fun `the stepper's arrows stop at the ends of the sizes`() {
        assertThat(panel(idle, chosen = 32.0).canStepUp).isFalse()
        assertThat(panel(idle, chosen = 32.0).canStepDown).isTrue()
        assertThat(panel(idle, chosen = 1.0 / 32).canStepDown).isFalse()
    }

    @Test
    fun `the loop region follows the loop state`() {
        assertThat(panel(idle).region).isNull()
        assertThat(panel(marking).region!!.phase).isEqualTo(io.sonora.feature.dj.waveform.LoopPhase.MARKING)
        assertThat(panel(armed).region!!.phase).isEqualTo(io.sonora.feature.dj.waveform.LoopPhase.ARMED)
        assertThat(panel(looping).region!!.phase).isEqualTo(io.sonora.feature.dj.waveform.LoopPhase.LOOPING)
        assertThat(panel(exited).region!!.phase).isEqualTo(io.sonora.feature.dj.waveform.LoopPhase.EXITED)
    }
}
