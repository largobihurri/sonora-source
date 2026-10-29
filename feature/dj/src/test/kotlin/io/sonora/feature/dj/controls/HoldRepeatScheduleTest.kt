package io.sonora.feature.dj.controls

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class HoldRepeatScheduleTest {

    private val schedule = HoldRepeatSchedule.Default

    @Test
    fun `the first repeat comes after the initial delay`() {
        assertThat(schedule.delayBeforeRepeat(0)).isEqualTo(400L)
    }

    @Test
    fun `the repeats then start at 120 ms`() {
        assertThat(schedule.delayBeforeRepeat(1)).isEqualTo(120L)
    }

    @Test
    fun `the delay between repeats only ever shrinks`() {
        val delays = (1..60).map(schedule::delayBeforeRepeat)

        assertThat(delays.zipWithNext().all { (a, b) -> b <= a }).isTrue()
        assertThat(delays.first()).isGreaterThan(delays[5]) // it really accelerates at first
    }

    @Test
    fun `the delay reaches 40 ms and stays there`() {
        assertThat(schedule.delayBeforeRepeat(40)).isEqualTo(40L)
        assertThat(schedule.delayBeforeRepeat(1_000)).isEqualTo(40L)
        assertThat(schedule.delayBeforeRepeat(Int.MAX_VALUE)).isEqualTo(40L)
    }

    @Test
    fun `no delay is shorter than the fastest one`() {
        assertThat((1..500).minOf(schedule::delayBeforeRepeat)).isEqualTo(40L)
    }

    @Test
    fun `the fastest rate is reached within about a second and a half of holding`() {
        // The time the n-th repeat fires at, from the press.
        fun firesAt(repeat: Int): Long = (0 until repeat).sumOf { schedule.delayBeforeRepeat(it) }

        val firstAtTopSpeed = (1..100).first { schedule.delayBeforeRepeat(it) == 40L }

        assertThat(firesAt(firstAtTopSpeed)).isAtMost(1_500L)
    }

    @Test
    fun `a custom schedule follows its parameters`() {
        val custom = HoldRepeatSchedule(initialDelayMs = 500, startIntervalMs = 200, minIntervalMs = 100, decayPercent = 50)

        assertThat(custom.delayBeforeRepeat(0)).isEqualTo(500L)
        assertThat(custom.delayBeforeRepeat(1)).isEqualTo(200L)
        assertThat(custom.delayBeforeRepeat(2)).isEqualTo(100L)
        assertThat(custom.delayBeforeRepeat(3)).isEqualTo(100L)
    }

    @Test
    fun `a schedule that cannot work is refused`() {
        assertThrows(IllegalArgumentException::class.java) { HoldRepeatSchedule(400, 120, 0, 90) } // no delay at all would spin
        assertThrows(IllegalArgumentException::class.java) { HoldRepeatSchedule(400, 30, 40, 90) } // starts faster than the floor
        assertThrows(IllegalArgumentException::class.java) { HoldRepeatSchedule(0, 120, 40, 90) }
        assertThrows(IllegalArgumentException::class.java) { HoldRepeatSchedule(400, 120, 40, 100) } // would never accelerate
        assertThrows(IllegalArgumentException::class.java) { HoldRepeatSchedule(400, 120, 40, 0) }
    }

    @Test
    fun `a negative repeat is refused`() {
        assertThrows(IllegalArgumentException::class.java) { schedule.delayBeforeRepeat(-1) }
    }
}
