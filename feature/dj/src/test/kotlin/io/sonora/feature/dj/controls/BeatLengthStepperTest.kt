package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

/** The beat-length stepper of the loop controls: 1/32 to 32 beats, as the loop engine offers them. */
class BeatLengthStepperTest {

    /** 120 BPM from 0 ms: a beat every 500 ms. */
    private val grid = DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1)))

    @Test
    fun `the sizes are the eleven the loop engine offers, a beat length doubling each step`() {
        assertThat(BeatLengthStepper.SIZES).hasSize(11)
        assertThat(BeatLengthStepper.SIZES.first()).isEqualTo(1.0 / 32)
        assertThat(BeatLengthStepper.SIZES.last()).isEqualTo(32.0)
    }

    @Test
    fun `the labels read as fractions below one beat and whole numbers from one`() {
        val labels = BeatLengthStepper.SIZES.map(BeatLengthStepper::label)
        assertThat(labels).containsExactly("1/32", "1/16", "1/8", "1/4", "1/2", "1", "2", "4", "8", "16", "32").inOrder()
    }

    @Test
    fun `a loop opens at four beats`() {
        assertThat(BeatLengthStepper.DEFAULT).isEqualTo(4.0)
    }

    @Test
    fun `stepping moves one size up or down`() {
        assertThat(BeatLengthStepper.stepped(4.0, +1)).isEqualTo(8.0)
        assertThat(BeatLengthStepper.stepped(4.0, -1)).isEqualTo(2.0)
        assertThat(BeatLengthStepper.stepped(1.0, -1)).isEqualTo(0.5)
        assertThat(BeatLengthStepper.stepped(1.0 / 16, -1)).isEqualTo(1.0 / 32)
    }

    @Test
    fun `stepping stops at both ends`() {
        assertThat(BeatLengthStepper.stepped(32.0, +1)).isEqualTo(32.0)
        assertThat(BeatLengthStepper.stepped(1.0 / 32, -1)).isEqualTo(1.0 / 32)
        assertThat(BeatLengthStepper.canStep(32.0, +1)).isFalse()
        assertThat(BeatLengthStepper.canStep(32.0, -1)).isTrue()
        assertThat(BeatLengthStepper.canStep(1.0 / 32, -1)).isFalse()
        assertThat(BeatLengthStepper.canStep(1.0 / 32, +1)).isTrue()
    }

    @Test
    fun `a length between two sizes steps to the next size in the direction asked`() {
        assertThat(BeatLengthStepper.stepped(3.0, +1)).isEqualTo(4.0)
        assertThat(BeatLengthStepper.stepped(3.0, -1)).isEqualTo(2.0)
        assertThat(BeatLengthStepper.stepped(100.0, -1)).isEqualTo(32.0)
        assertThat(BeatLengthStepper.stepped(0.001, +1)).isEqualTo(1.0 / 32)
    }

    @Test
    fun `a delta of zero keeps the size nearest to it`() {
        assertThat(BeatLengthStepper.stepped(4.0, 0)).isEqualTo(4.0)
        assertThat(BeatLengthStepper.stepped(3.1, 0)).isEqualTo(4.0)
    }

    @Test
    fun `a loop of whole sizes measures as the size it is`() {
        assertThat(BeatLengthStepper.measured(1_000.0, 3_000.0, grid, 240_000.0)).isEqualTo(4.0)
        assertThat(BeatLengthStepper.measured(1_000.0, 1_500.0, grid, 240_000.0)).isEqualTo(1.0)
        assertThat(BeatLengthStepper.measured(2_000.0, 2_031.25, grid, 240_000.0)).isEqualTo(1.0 / 16)
        assertThat(BeatLengthStepper.measured(0.0, 16_000.0, grid, 240_000.0)).isEqualTo(32.0)
    }

    @Test
    fun `a loop a few percent off a size still measures as it, as after a halving on the frame grid`() {
        assertThat(BeatLengthStepper.measured(1_000.0, 2_990.0, grid, 240_000.0)).isEqualTo(4.0)
    }

    @Test
    fun `a manual loop between sizes, or on a track without a grid, measures as nothing`() {
        assertThat(BeatLengthStepper.measured(1_000.0, 1_900.0, grid, 240_000.0)).isNull()
        assertThat(BeatLengthStepper.measured(1_000.0, 3_000.0, null, 240_000.0)).isNull()
        assertThat(BeatLengthStepper.measured(3_000.0, 1_000.0, grid, 240_000.0)).isNull()
    }

    @Test
    fun `what the stepper shows is the measured length of the loop that is set, else the chosen one`() {
        assertThat(BeatLengthStepper.shown(chosen = 8.0, measured = 2.0)).isEqualTo(2.0)
        assertThat(BeatLengthStepper.shown(chosen = 8.0, measured = null)).isEqualTo(8.0)
    }
}
