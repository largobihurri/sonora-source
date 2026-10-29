package io.sonora.player.wear.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RotaryVolumeStepperTest {

    @Test
    fun `a turn shorter than a step does nothing yet`() {
        assertThat(RotaryVolumeStepper(100f).add(40f)).isEqualTo(0)
    }

    @Test
    fun `small turns add up to a step`() {
        val stepper = RotaryVolumeStepper(100f)
        assertThat(stepper.add(40f)).isEqualTo(0)
        assertThat(stepper.add(40f)).isEqualTo(0)
        assertThat(stepper.add(40f)).isEqualTo(1)
    }

    @Test
    fun `a long turn is several steps and the remainder is kept`() {
        val stepper = RotaryVolumeStepper(100f)
        assertThat(stepper.add(250f)).isEqualTo(2)
        assertThat(stepper.add(50f)).isEqualTo(1)
    }

    @Test
    fun `turning the other way lowers the volume`() {
        val stepper = RotaryVolumeStepper(100f)
        assertThat(stepper.add(-250f)).isEqualTo(-2)
        assertThat(stepper.add(-50f)).isEqualTo(-1)
    }

    @Test
    fun `reversing first cancels what was pending`() {
        val stepper = RotaryVolumeStepper(100f)
        assertThat(stepper.add(60f)).isEqualTo(0)
        assertThat(stepper.add(-60f)).isEqualTo(0)
        assertThat(stepper.add(60f)).isEqualTo(0)
    }

    @Test
    fun `reset forgets the pending turn`() {
        val stepper = RotaryVolumeStepper(100f)
        stepper.add(90f)
        stepper.reset()
        assertThat(stepper.add(20f)).isEqualTo(0)
    }

    @Test
    fun `a step size of zero is refused`() {
        assertThrows(IllegalArgumentException::class.java) { RotaryVolumeStepper(0f) }
    }
}
