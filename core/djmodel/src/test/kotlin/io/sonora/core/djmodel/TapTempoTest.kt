package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TapTempoTest {

    private var nowNs = 1_000_000_000L
    private val tapTempo = TapTempo { nowNs }

    /** Taps once, [afterMs] milliseconds after the previous tap (or after the start of the clock). */
    private fun tap(afterMs: Long): Int? {
        nowNs += afterMs * 1_000_000L
        return tapTempo.tap()
    }

    /** Taps the intervals in order, returning what the last tap proposed. */
    private fun tapAll(first: Long, vararg intervals: Long): Int? {
        var proposal = tap(first)
        for (interval in intervals) proposal = tap(interval)
        return proposal
    }

    @Test
    fun `no tempo is proposed before the fourth tap`() {
        assertThat(tap(0)).isNull()
        assertThat(tap(500)).isNull()
        assertThat(tap(500)).isNull()
        assertThat(tapTempo.tapCount).isEqualTo(3)
        assertThat(tapTempo.bpmHundredths).isNull()
    }

    @Test
    fun `four steady taps propose the tempo`() {
        val proposal = tapAll(0, 500, 500, 500)

        assertThat(proposal).isEqualTo(12_000)
        assertThat(tapTempo.bpmHundredths).isEqualTo(12_000)
        assertThat(tapTempo.tapCount).isEqualTo(4)
    }

    @Test
    fun `the tempo is rounded to the hundredth of a BPM`() {
        // 60000 / 470 = 127.659574...
        assertThat(tapAll(0, 470, 470, 470)).isEqualTo(12_766)
    }

    @Test
    fun `the clock is read in nanoseconds so a millisecond of jitter does not cost a quarter of a BPM`() {
        nowNs += 1
        tapTempo.tap()
        nowNs += 468_750_000L // 128.00 BPM exactly
        tapTempo.tap()
        nowNs += 468_750_000L
        tapTempo.tap()
        nowNs += 468_750_000L

        assertThat(tapTempo.tap()).isEqualTo(12_800)
    }

    @Test
    fun `only the last eight taps count`() {
        tapAll(0, 500, 500, 500) // 120 BPM
        val proposal = tapAll(400, 400, 400, 400, 400, 400, 400, 400) // 150 BPM, enough taps to push the old ones out

        assertThat(tapTempo.tapCount).isEqualTo(TapTempo.MAX_TAPS)
        assertThat(proposal).isEqualTo(15_000)
    }

    @Test
    fun `the proposal follows the tempo as more taps arrive`() {
        tapAll(0, 500, 500, 500)
        assertThat(tapTempo.bpmHundredths).isEqualTo(12_000)

        // Slowing down: the mean of the intervals in the window moves towards the new tempo.
        assertThat(tap(550)).isEqualTo(tapTempo.bpmHundredths)
        assertThat(tapTempo.bpmHundredths).isLessThan(12_000)
    }

    @Test
    fun `a pause longer than two seconds starts again`() {
        tapAll(0, 500, 500, 500)

        assertThat(tap(2_001)).isNull()
        assertThat(tapTempo.tapCount).isEqualTo(1)
        assertThat(tapTempo.bpmHundredths).isNull()

        // The taps before the pause do not leak into the new run.
        assertThat(tapAll(400, 400, 400)).isEqualTo(15_000)
    }

    @Test
    fun `a pause of exactly two seconds does not start again`() {
        tapAll(0, 500, 500)

        tap(2_000)

        assertThat(tapTempo.tapCount).isEqualTo(4)
    }

    @Test
    fun `the proposal stays readable during a pause and goes with the next tap`() {
        tapAll(0, 500, 500, 500)
        nowNs += 10_000_000_000L // the clock moves on, nobody taps

        assertThat(tapTempo.bpmHundredths).isEqualTo(12_000)

        tapTempo.tap()

        assertThat(tapTempo.bpmHundredths).isNull()
    }

    @Test
    fun `an interval far from the median is ignored`() {
        // A missed beat: 950 ms among 500 ms intervals.
        assertThat(tapAll(0, 500, 500, 950, 500, 500)).isEqualTo(12_000)
    }

    @Test
    fun `a double tap bounce is ignored`() {
        assertThat(tapAll(0, 500, 500, 80, 500, 500)).isEqualTo(12_000)
    }

    @Test
    fun `an interval exactly thirty percent off the median is kept and one beyond is not`() {
        // Median 500: 650 is exactly 30 % over and counts; the mean of 500, 500, 650 is 550 ms.
        assertThat(tapAll(0, 500, 500, 650)).isEqualTo(10_909)
        tapTempo.reset()
        // 651 is beyond 30 %: dropped, so the mean is the 500 ms of the other two.
        assertThat(tapAll(0, 500, 500, 651)).isEqualTo(12_000)
    }

    @Test
    fun `an even count of intervals takes the median between the two middle ones`() {
        // Median of 400, 400, 700, 700 is 550; each is within 30 % of it, so none is ignored: the mean is 550 ms.
        assertThat(tapAll(0, 400, 700, 400, 700)).isEqualTo(10_909)
    }

    @Test
    fun `no tempo is proposed when fewer than two intervals agree`() {
        // Median 500: 300 is 40 % under and 900 is 80 % over, leaving a single interval.
        assertThat(tapAll(0, 300, 500, 900)).isNull()
    }

    @Test
    fun `a tempo no edit can hold is not proposed`() {
        assertThat(tapAll(0, 20, 20, 20)).isNull() // 3000 BPM
        tapTempo.reset()
        assertThat(tapAll(0, 1_900, 1_900, 1_900)).isEqualTo(3_158) // 31.58 BPM is slow but valid
    }

    @Test
    fun `the proposal always fits the range of a grid edit`() {
        val fastest = tapAll(0, 150, 150, 150)

        assertThat(fastest).isEqualTo(GridEdit.MAX_BPM_HUNDREDTHS)
    }

    @Test
    fun `reset forgets every tap`() {
        tapAll(0, 500, 500, 500)

        tapTempo.reset()

        assertThat(tapTempo.tapCount).isEqualTo(0)
        assertThat(tapTempo.bpmHundredths).isNull()
        assertThat(tap(500)).isNull() // a new run: the long gap since the last tap is not an interval
        assertThat(tapTempo.tapCount).isEqualTo(1)
    }
}
