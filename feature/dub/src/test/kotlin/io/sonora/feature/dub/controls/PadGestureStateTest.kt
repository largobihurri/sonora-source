package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PadGestureStateTest {

    @Test
    fun `a down engages at once with no wait for a double tap`() {
        val pad = PadGestureState()
        assertThat(pad.down(1_000)).isTrue()
        assertThat(pad.state).isEqualTo(PadState.HELD)
        assertThat(pad.engaged).isTrue()
    }

    @Test
    fun `lifting a held pad releases it`() {
        val pad = PadGestureState()
        pad.down(1_000)
        assertThat(pad.up(1_400)).isTrue()
        assertThat(pad.state).isEqualTo(PadState.OFF)
        assertThat(pad.engaged).isFalse()
    }

    @Test
    fun `a second down within 250 ms of the previous up latches and stays latched after its up`() {
        val pad = PadGestureState()
        pad.down(1_000)
        pad.up(1_080)
        // Already off after the first up, so the second down engages again.
        assertThat(pad.down(1_330)).isTrue()
        assertThat(pad.state).isEqualTo(PadState.LATCHED)
        assertThat(pad.up(1_400)).isFalse()
        assertThat(pad.state).isEqualTo(PadState.LATCHED)
        assertThat(pad.engaged).isTrue()
    }

    @Test
    fun `a second down later than 250 ms after the up is a new hold`() {
        val pad = PadGestureState()
        pad.down(1_000)
        pad.up(1_080)
        pad.down(1_331)
        assertThat(pad.state).isEqualTo(PadState.HELD)
        pad.up(1_500)
        assertThat(pad.state).isEqualTo(PadState.OFF)
    }

    @Test
    fun `the next tap after a latch unlatches at its down and its up changes nothing`() {
        val pad = latchedPad()
        assertThat(pad.down(5_000)).isTrue()
        assertThat(pad.state).isEqualTo(PadState.OFF)
        assertThat(pad.up(5_050)).isFalse()
        assertThat(pad.state).isEqualTo(PadState.OFF)
    }

    @Test
    fun `the unlatching tap does not start a double tap`() {
        val pad = latchedPad()
        pad.down(5_000)
        pad.up(5_050)
        pad.down(5_150)
        assertThat(pad.state).isEqualTo(PadState.HELD)
    }

    @Test
    fun `a pointer cancel releases a held pad`() {
        val pad = PadGestureState()
        pad.down(1_000)
        assertThat(pad.cancel()).isTrue()
        assertThat(pad.state).isEqualTo(PadState.OFF)
    }

    @Test
    fun `a cancel during the latching press leaves the pad off`() {
        val pad = PadGestureState()
        pad.down(1_000)
        pad.up(1_050)
        pad.down(1_150)
        assertThat(pad.cancel()).isTrue()
        assertThat(pad.state).isEqualTo(PadState.OFF)
    }

    @Test
    fun `a cancelled press does not count as the first tap of a double tap`() {
        val pad = PadGestureState()
        pad.down(1_000)
        pad.cancel()
        pad.down(1_100)
        assertThat(pad.state).isEqualTo(PadState.HELD)
    }

    @Test
    fun `two pads held at once with two fingers are both engaged and release on their own`() {
        val throwPad = PadGestureState()
        val cutPad = PadGestureState()
        throwPad.down(1_000)
        cutPad.down(1_010)
        assertThat(throwPad.engaged).isTrue()
        assertThat(cutPad.engaged).isTrue()
        throwPad.up(1_500)
        assertThat(throwPad.engaged).isFalse()
        assertThat(cutPad.engaged).isTrue()
        // The other pad's up never makes this one's next down a double tap.
        cutPad.up(1_600)
        cutPad.down(2_000)
        assertThat(cutPad.state).isEqualTo(PadState.HELD)
    }

    @Test
    fun `the latch and release actions operate a pad without holding it`() {
        val pad = PadGestureState()
        assertThat(pad.latch()).isTrue()
        assertThat(pad.state).isEqualTo(PadState.LATCHED)
        assertThat(pad.latch()).isFalse()
        assertThat(pad.release()).isTrue()
        assertThat(pad.state).isEqualTo(PadState.OFF)
        assertThat(pad.release()).isFalse()
    }

    @Test
    fun `latching a held pad keeps it engaged after the finger lifts`() {
        val pad = PadGestureState()
        pad.down(1_000)
        assertThat(pad.latch()).isFalse()
        pad.up(1_500)
        assertThat(pad.state).isEqualTo(PadState.LATCHED)
    }

    @Test
    fun `leaving the screen releases a held pad and keeps a latched one`() {
        val held = PadGestureState()
        held.down(1_000)
        assertThat(held.releaseMomentary()).isTrue()
        assertThat(held.state).isEqualTo(PadState.OFF)
        // The up of the abandoned touch arrives later and changes nothing.
        assertThat(held.up(1_200)).isFalse()

        val latched = latchedPad()
        assertThat(latched.releaseMomentary()).isFalse()
        assertThat(latched.state).isEqualTo(PadState.LATCHED)
    }

    @Test
    fun `an up or a cancel with no press changes nothing`() {
        val pad = PadGestureState()
        assertThat(pad.up(1_000)).isFalse()
        assertThat(pad.cancel()).isFalse()
        assertThat(pad.state).isEqualTo(PadState.OFF)
    }

    @Test
    fun `a second down while a finger is already on the pad is ignored`() {
        val pad = PadGestureState()
        pad.down(1_000)
        assertThat(pad.down(1_010)).isFalse()
        assertThat(pad.state).isEqualTo(PadState.HELD)
    }

    private fun latchedPad(): PadGestureState = PadGestureState().apply {
        down(1_000)
        up(1_050)
        down(1_150)
        up(1_200)
        check(state == PadState.LATCHED)
    }
}
