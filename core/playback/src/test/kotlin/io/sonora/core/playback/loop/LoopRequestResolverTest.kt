package io.sonora.core.playback.loop

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djloop.LoopCommand
import io.sonora.core.djloop.LoopState
import io.sonora.core.djloop.LoopStatus
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.djmodel.loop.LoopFrames
import io.sonora.core.djmodel.loop.LoopQuantizer
import org.junit.Test

/** Turning the user's button presses into engine commands, at the frame that is being heard. */
class LoopRequestResolverTest {

    private val rate = 48_000
    private val delay = 1_105L
    private val offset = 48_000_000_000L
    private val time = StreamTimeBase(rate, encoderDelayFrames = delay, streamOffsetFrames = offset, trimmedFrames = delay)

    /** 120 BPM: a beat every 500 ms from 0. */
    private val grid = LoopQuantizer(DjBeatGrid(listOf(TempoSegment(0.0, 120.0, 1))), durationMs = 240_000.0)
    private val gridless = LoopQuantizer(null, durationMs = 240_000.0)

    private fun context(quantizer: LoopQuantizer? = grid, quantize: Boolean = true) = LoopContext(quantizer, quantize)

    private fun frame(gridMs: Double) = time.gridMsToFrame(gridMs)

    private fun resolve(
        request: LoopRequest,
        nowMs: Double,
        context: LoopContext = context(),
        markedIn: Long? = null,
        status: LoopStatus = LoopStatus.OFF,
    ) = LoopRequestResolver.resolve(request, frame(nowMs), time, context, markedIn, status)

    private fun playing(inMs: Double, outMs: Double) =
        LoopStatus(LoopState.LOOPING, time.gridFrames(inMs, outMs), false)

    // --- Loop In and Loop Out -------------------------------------------------------------------------------------

    @Test
    fun `Loop In with Quantize marks the nearest beat and reserves memory for the Out to come`() {
        val result = resolve(LoopRequest.MarkIn, nowMs = 1_230.0)

        assertThat(result.refusal).isNull()
        assertThat(result.markedIn).isEqualTo(frame(1_000.0))
        assertThat(result.commands).hasSize(2)
        assertThat(result.commands[0]).isEqualTo(LoopCommand.Clear)
        val reserve = result.commands[1] as LoopCommand.Reserve
        assertThat(reserve.frames).isAtLeast(10L * rate)
    }

    @Test
    fun `Loop In without Quantize marks the audible frame itself`() {
        val result = resolve(LoopRequest.MarkIn, nowMs = 1_230.0, context = context(quantize = false))

        assertThat(result.markedIn).isEqualTo(frame(1_230.0))
    }

    @Test
    fun `Loop In on a track without analysis marks the audible frame`() {
        val result = resolve(LoopRequest.MarkIn, nowMs = 1_230.0, context = LoopContext.NONE)

        assertThat(result.markedIn).isEqualTo(frame(1_230.0))
        assertThat(result.refusal).isNull()
    }

    @Test
    fun `Loop Out closes the loop from the marked In to the nearest beat and drops the mark`() {
        val result = resolve(LoopRequest.MarkOut, nowMs = 3_900.0, markedIn = frame(1_000.0))

        assertThat(result.refusal).isNull()
        assertThat(result.commands).containsExactly(LoopCommand.Set(time.gridFrames(1_000.0, 4_000.0)))
        assertThat(result.markedIn).isNull()
    }

    @Test
    fun `Loop Out without Quantize repeats exactly from In to the audible frame`() {
        val ctx = context(quantize = false)

        val result = resolve(LoopRequest.MarkOut, nowMs = 3_912.5, context = ctx, markedIn = frame(1_037.0))

        val set = result.commands.single() as LoopCommand.Set
        assertThat(set.loop.inFrame).isEqualTo(frame(1_037.0))
        assertThat(set.loop.outFrame).isEqualTo(frame(3_912.5))
    }

    @Test
    fun `Loop Out on the same beat as In makes a one beat loop`() {
        val result = resolve(LoopRequest.MarkOut, nowMs = 1_040.0, markedIn = frame(1_000.0))

        assertThat(result.commands.single()).isEqualTo(LoopCommand.Set(time.gridFrames(1_000.0, 1_500.0)))
    }

    @Test
    fun `Loop Out without a Loop In does nothing`() {
        val result = resolve(LoopRequest.MarkOut, nowMs = 3_900.0, markedIn = null)

        assertThat(result.commands).isEmpty()
        assertThat(result.refusal).isEqualTo(LoopRefusal.NO_IN)
    }

    @Test
    fun `a loop shorter than the minimum is refused and the mark is kept`() {
        val ctx = context(quantize = false)

        val result = resolve(LoopRequest.MarkOut, nowMs = 1_003.0, context = ctx, markedIn = frame(1_000.0))

        assertThat(result.commands).isEmpty()
        assertThat(result.refusal).isEqualTo(LoopRefusal.TOO_SHORT)
        assertThat(result.markedIn).isEqualTo(frame(1_000.0))
    }

    @Test
    fun `Loop Out without analysis uses the frames as they are`() {
        val result = resolve(LoopRequest.MarkOut, nowMs = 5_000.0, context = LoopContext.NONE, markedIn = frame(2_000.0))

        assertThat(result.commands.single()).isEqualTo(LoopCommand.Set(LoopFrames(frame(2_000.0), frame(5_000.0))))
    }

    @Test
    fun `a manual loop on a gridless track keeps its points`() {
        val result = resolve(LoopRequest.MarkOut, nowMs = 5_000.0, context = context(gridless), markedIn = frame(2_000.0))

        assertThat(result.commands.single()).isEqualTo(LoopCommand.Set(time.gridFrames(2_000.0, 5_000.0)))
    }

    // --- Beat loops -----------------------------------------------------------------------------------------------

    @Test
    fun `a four beat loop starts on the nearest beat with Quantize`() {
        val result = resolve(LoopRequest.BeatLoop(4.0), nowMs = 2_300.0)

        assertThat(result.commands.single()).isEqualTo(LoopCommand.Set(time.gridFrames(2_500.0, 4_500.0)))
        assertThat(result.markedIn).isNull()
    }

    @Test
    fun `a beat loop without Quantize starts at the audible frame`() {
        val result = resolve(LoopRequest.BeatLoop(1.0), nowMs = 2_300.0, context = context(quantize = false))

        assertThat(result.commands.single()).isEqualTo(LoopCommand.Set(time.gridFrames(2_300.0, 2_800.0)))
    }

    @Test
    fun `a beat loop pressed while a loop plays keeps its In`() {
        val status = playing(inMs = 2_000.0, outMs = 6_000.0)

        val result = resolve(LoopRequest.BeatLoop(2.0), nowMs = 4_700.0, status = status)

        assertThat(result.commands.single()).isEqualTo(LoopCommand.Set(time.gridFrames(2_000.0, 3_000.0)))
    }

    @Test
    fun `a beat loop after Exit starts again at the beat nearest to now`() {
        val exited = LoopStatus(LoopState.OFF, time.gridFrames(2_000.0, 6_000.0), false)

        val result = resolve(LoopRequest.BeatLoop(2.0), nowMs = 4_700.0, status = exited)

        assertThat(result.commands.single()).isEqualTo(LoopCommand.Set(time.gridFrames(4_500.0, 5_500.0)))
    }

    @Test
    fun `a beat loop needs a grid`() {
        for (ctx in listOf(context(gridless), LoopContext.NONE)) {
            val result = resolve(LoopRequest.BeatLoop(4.0), nowMs = 2_300.0, context = ctx)

            assertThat(result.commands).isEmpty()
            assertThat(result.refusal).isEqualTo(LoopRefusal.NO_GRID)
        }
    }

    @Test
    fun `a beat loop that would end after the track is refused`() {
        val result = resolve(LoopRequest.BeatLoop(32.0), nowMs = 235_000.0)

        assertThat(result.commands).isEmpty()
        assertThat(result.refusal).isEqualTo(LoopRefusal.PAST_END)
    }

    // --- Halve, double, exit, reloop, clear -----------------------------------------------------------------------

    @Test
    fun `halve and double go to the engine when there is a loop`() {
        val status = playing(inMs = 2_000.0, outMs = 6_000.0)

        assertThat(resolve(LoopRequest.HalveLoop, 3_000.0, status = status).commands).containsExactly(LoopCommand.Halve)
        assertThat(resolve(LoopRequest.DoubleLoop, 3_000.0, status = status).commands).containsExactly(LoopCommand.Double)
    }

    @Test
    fun `halve and double without a loop are refused`() {
        for (request in listOf(LoopRequest.HalveLoop, LoopRequest.DoubleLoop)) {
            val result = resolve(request, 3_000.0)

            assertThat(result.commands).isEmpty()
            assertThat(result.refusal).isEqualTo(LoopRefusal.NO_LOOP)
        }
    }

    @Test
    fun `doubling a loop that would end after the track is refused`() {
        val status = playing(inMs = 120_000.0, outMs = 200_000.0)

        val result = resolve(LoopRequest.DoubleLoop, 150_000.0, status = status)

        assertThat(result.commands).isEmpty()
        assertThat(result.refusal).isEqualTo(LoopRefusal.PAST_END)
    }

    @Test
    fun `exit and reloop are passed on and clear forgets the mark too`() {
        assertThat(resolve(LoopRequest.ExitLoop, 3_000.0).commands).containsExactly(LoopCommand.Exit)
        assertThat(resolve(LoopRequest.Reloop, 3_000.0).commands).containsExactly(LoopCommand.Reloop)

        val cleared = resolve(LoopRequest.ClearLoop, 3_000.0, markedIn = frame(1_000.0))
        assertThat(cleared.commands).containsExactly(LoopCommand.Clear)
        assertThat(cleared.markedIn).isNull()
    }

    @Test
    fun `exit keeps a pending Loop In mark`() {
        val result = resolve(LoopRequest.ExitLoop, 3_000.0, markedIn = frame(1_000.0))

        assertThat(result.markedIn).isEqualTo(frame(1_000.0))
    }

    // --- Saved loops (a hot loop or a memory loop recalled by its grid times) --------------------------------------

    @Test
    fun `Engage sets the loop at its grid times, whatever the encoder delay, and forgets a pending Loop In`() {
        val result = resolve(LoopRequest.Engage(1_000.0, 3_000.0), nowMs = 1_500.0, markedIn = frame(200.0))

        assertThat(result.refusal).isNull()
        assertThat(result.commands).containsExactly(LoopCommand.Set(time.gridFrames(1_000.0, 3_000.0)))
        assertThat(result.markedIn).isNull()
    }

    @Test
    fun `Engage while the audio heard is inside the loop does not ask for a jump`() {
        val result = resolve(LoopRequest.Engage(1_000.0, 3_000.0), nowMs = 2_000.0)

        assertThat(result.jumpToFrame).isNull()
    }

    @Test
    fun `Engage while the audio heard is before or after the loop jumps to its In`() {
        val before = resolve(LoopRequest.Engage(10_000.0, 12_000.0), nowMs = 2_000.0)
        val after = resolve(LoopRequest.Engage(1_000.0, 3_000.0), nowMs = 3_000.0)

        assertThat(before.jumpToFrame).isEqualTo(frame(10_000.0))
        assertThat(after.jumpToFrame).isEqualTo(frame(1_000.0))
    }

    @Test
    fun `Engage works without a beat grid and ignores Quantize`() {
        val result = resolve(LoopRequest.Engage(1_234.5, 2_345.5), nowMs = 0.0, context = context(gridless, quantize = true))

        assertThat(result.commands).containsExactly(LoopCommand.Set(time.gridFrames(1_234.5, 2_345.5)))
    }

    @Test
    fun `Engage of an empty or reversed span is refused as too short`() {
        assertThat(resolve(LoopRequest.Engage(2_000.0, 2_000.0), nowMs = 0.0).refusal).isEqualTo(LoopRefusal.TOO_SHORT)
        assertThat(resolve(LoopRequest.Engage(3_000.0, 2_000.0), nowMs = 0.0).refusal).isEqualTo(LoopRefusal.TOO_SHORT)
    }
}
