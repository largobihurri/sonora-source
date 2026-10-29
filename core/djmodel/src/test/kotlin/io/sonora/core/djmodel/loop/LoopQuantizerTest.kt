package io.sonora.core.djmodel.loop

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import org.junit.Test

class LoopQuantizerTest {

    /** 120 BPM (500 ms beats) with a beat at 1300 ms, so beats at 300, 800, 1300, 1800 ... */
    private val constant = LoopQuantizer(DjBeatGrid(listOf(TempoSegment(1300.0, 120.0, 1))), durationMs = 60_000.0)

    /** 120 BPM from 0 ms, then 60 BPM (1000 ms beats) from the beat at 4000 ms. */
    private val variable = LoopQuantizer(
        DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1), TempoSegment(4000.0, 60.0, 1))),
        durationMs = 20_000.0,
    )

    private val gridless = LoopQuantizer(null, durationMs = 60_000.0)

    @Test
    fun `hasGrid tells whether the track has beats to snap to`() {
        assertThat(constant.hasGrid).isTrue()
        assertThat(variable.hasGrid).isTrue()
        assertThat(gridless.hasGrid).isFalse()
    }

    @Test
    fun `snap goes to the nearest beat and a tie to the later one`() {
        assertThat(constant.snap(1500.0)).isWithin(1e-9).of(1300.0)
        assertThat(constant.snap(1600.0)).isWithin(1e-9).of(1800.0)
        assertThat(constant.snap(1550.0)).isWithin(1e-9).of(1800.0)
        assertThat(constant.snap(1300.0)).isWithin(1e-9).of(1300.0)
    }

    @Test
    fun `snap never leaves the track`() {
        // The beat before 300 ms would be at -200 ms.
        assertThat(constant.snap(0.0)).isWithin(1e-9).of(300.0)
        assertThat(constant.snap(50.0)).isWithin(1e-9).of(300.0)
        // Near the end the nearest beat may lie past it; the one before is used.
        val short = LoopQuantizer(DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1))), durationMs = 1900.0)
        assertThat(short.snap(1890.0)).isWithin(1e-9).of(1500.0)
    }

    @Test
    fun `snap follows tempo changes`() {
        assertThat(variable.snap(3900.0)).isWithin(1e-9).of(4000.0)
        assertThat(variable.snap(4400.0)).isWithin(1e-9).of(4000.0)
        assertThat(variable.snap(4600.0)).isWithin(1e-9).of(5000.0)
        assertThat(variable.snap(2200.0)).isWithin(1e-9).of(2000.0)
    }

    @Test
    fun `without a grid nothing snaps and only manual loops exist`() {
        assertThat(gridless.snap(1234.5)).isEqualTo(1234.5)
        assertThat(gridless.beatLoop(1000.0, 4.0)).isNull()
        assertThat(gridless.manualLoop(1234.5, 2000.0, quantize = true)).isEqualTo(Loop(1234.5, 2000.0))
    }

    @Test
    fun `beat positions and times convert both ways across segments`() {
        assertThat(variable.beatPositionAt(2000.0)).isWithin(1e-9).of(4.0)
        assertThat(variable.beatPositionAt(4000.0)).isWithin(1e-9).of(8.0)
        assertThat(variable.beatPositionAt(5500.0)).isWithin(1e-9).of(9.5)
        assertThat(variable.timeAtBeatPosition(9.5)).isWithin(1e-9).of(5500.0)
        assertThat(variable.timeAtBeatPosition(7.5)).isWithin(1e-9).of(3750.0)
        assertThat(constant.timeAtBeatPosition(constant.beatPositionAt(777.0))).isWithin(1e-9).of(777.0)
    }

    @Test
    fun `a beat loop starts on the snapped beat and lasts that many beats`() {
        val loop = constant.beatLoop(1500.0, 4.0)!!

        assertThat(loop.inMs).isWithin(1e-9).of(1300.0)
        assertThat(loop.outMs).isWithin(1e-9).of(3300.0)
    }

    @Test
    fun `fractions of a beat subdivide the beat interval`() {
        assertThat(constant.beatLoop(1300.0, 0.5)!!.lengthMs).isWithin(1e-9).of(250.0)
        assertThat(constant.beatLoop(1300.0, 1.0 / 32)!!.lengthMs).isWithin(1e-9).of(15.625)
        assertThat(constant.beatLoop(1300.0, 32.0)!!.lengthMs).isWithin(1e-9).of(16_000.0)
    }

    @Test
    fun `without quantizing the start stays where it was`() {
        val loop = constant.beatLoop(1500.0, 1.0, quantizeIn = false)!!

        assertThat(loop.inMs).isEqualTo(1500.0)
        assertThat(loop.outMs).isWithin(1e-9).of(2000.0)
    }

    @Test
    fun `a beat loop counts beats across a tempo change`() {
        // From the beat at 3000 ms: 3500, 4000 (tempo changes), 5000, 6000.
        val loop = variable.beatLoop(3000.0, 4.0)!!

        assertThat(loop.inMs).isWithin(1e-9).of(3000.0)
        assertThat(loop.outMs).isWithin(1e-9).of(6000.0)
    }

    @Test
    fun `a loop that would end after the track is refused`() {
        assertThat(constant.beatLoop(59_000.0, 4.0)).isNull()
        assertThat(constant.beatLoop(1300.0, 4.0)).isNotNull()
    }

    @Test
    fun `halving keeps the start and halves the beats`() {
        val halved = constant.halve(Loop(1300.0, 3300.0))!!

        assertThat(halved.inMs).isWithin(1e-9).of(1300.0)
        assertThat(halved.outMs).isWithin(1e-9).of(2300.0)
    }

    @Test
    fun `halving counts beats not time on a track with tempo changes`() {
        // 4 beats from 3000 to 6000; half is 2 beats: 3500 and 4000.
        val halved = variable.halve(Loop(3000.0, 6000.0))!!

        assertThat(halved.outMs).isWithin(1e-9).of(4000.0)
    }

    @Test
    fun `halving stops at the minimum length`() {
        // 1/32 beat at 120 BPM is 15.6 ms, half is 7.8 ms, under the minimum.
        assertThat(constant.halve(Loop(1300.0, 1300.0 + 15.625))).isNull()
        assertThat(constant.halve(Loop(1300.0, 1300.0 + 31.25))).isNotNull()
    }

    @Test
    fun `doubling keeps the start and stops at the end of the track`() {
        val doubled = constant.double(Loop(1300.0, 2300.0))!!

        assertThat(doubled.outMs).isWithin(1e-9).of(3300.0)
        assertThat(constant.double(Loop(1300.0, 40_000.0))).isNull()
    }

    @Test
    fun `halving and doubling without a grid work on time`() {
        assertThat(gridless.halve(Loop(1000.0, 3000.0))).isEqualTo(Loop(1000.0, 2000.0))
        assertThat(gridless.double(Loop(1000.0, 3000.0))).isEqualTo(Loop(1000.0, 5000.0))
        assertThat(gridless.halve(Loop(1000.0, 1015.0))).isNull()
    }

    @Test
    fun `a manual loop snaps both points to beats`() {
        val loop = constant.manualLoop(1490.0, 3100.0)!!

        assertThat(loop.inMs).isWithin(1e-9).of(1300.0)
        assertThat(loop.outMs).isWithin(1e-9).of(3300.0)
    }

    @Test
    fun `a manual loop between the same beat lasts one beat`() {
        val loop = constant.manualLoop(1400.0, 1500.0)!!

        assertThat(loop.inMs).isWithin(1e-9).of(1300.0)
        assertThat(loop.outMs).isWithin(1e-9).of(1800.0)
    }

    @Test
    fun `an unquantized manual loop keeps both points`() {
        assertThat(constant.manualLoop(1490.0, 3010.0, quantize = false)).isEqualTo(Loop(1490.0, 3010.0))
    }

    @Test
    fun `a manual loop is held inside the track and refused when too short`() {
        val clipped = constant.manualLoop(59_000.0, 70_000.0, quantize = false)!!

        assertThat(clipped.outMs).isEqualTo(60_000.0)
        assertThat(constant.manualLoop(1000.0, 1005.0, quantize = false)).isNull()
        assertThat(constant.manualLoop(2000.0, 1000.0, quantize = false)).isNull()
        assertThat(constant.manualLoop(70_000.0, 71_000.0, quantize = false)).isNull()
    }
}
