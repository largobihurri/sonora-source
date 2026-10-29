package io.sonora.core.djloop

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.loop.LoopFrames
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

class LoopEngineTest {

    private val rate = 48_000

    private fun blendLength(loopFrames: Int) = LoopBlend.blendFrames(rate, loopFrames.toLong())

    // --- Pass through ---------------------------------------------------------------------------------------------

    @Test
    fun `without a loop the audio comes out unchanged`() {
        val h = EngineHarness(totalFrames = 5_000)

        h.runToEnd()

        assertThat(h.output.totalFrames).isEqualTo(5_000L)
        for (i in 0 until 5_000L) assertThat(h.sourceAt(i)).isEqualTo(i.toInt())
        assertThat(h.engine.status.state).isEqualTo(LoopState.OFF)
        assertThat(h.engine.timeline.sourceFrameAt(4_999)).isEqualTo(4_999L)
        assertThat(h.engine.timeline.wrapsAt(4_999)).isEqualTo(0L)
    }

    @Test
    fun `the buffers offered to the output are little endian, as Media3's sink requires`() {
        val h = EngineHarness(totalFrames = 2_000)

        h.runToEnd()

        assertThat(h.output.offeredBuffers).isNotEmpty()
        for (buffer in h.output.offeredBuffers) assertThat(buffer.order()).isEqualTo(java.nio.ByteOrder.LITTLE_ENDIAN)
    }

    @Test
    fun `the first presentation time after a flush sets the source frame count`() {
        val h = EngineHarness(totalFrames = 49_000, startFrame = 48_000)

        h.runToEnd()

        assertThat(h.sourceAt(0)).isEqualTo(48_000)
        assertThat(h.engine.timeline.sourceFrameAt(0)).isEqualTo(48_000L)
        assertThat(h.engine.timeline.sourceFrameAt(999)).isEqualTo(48_999L)
    }

    @Test
    fun `a full output makes the engine hold the audio and offer it again without losing or repeating any`() {
        val h = EngineHarness(totalFrames = 6_000)

        var target = 0L
        while (target < 6_000) {
            target += 300
            h.runTo(target)
        }

        assertThat(h.output.totalFrames).isEqualTo(6_000L)
        for (i in 0 until 6_000L) assertThat(h.sourceAt(i)).isEqualTo(i.toInt())
        // Every offer starts where the output stands: the sink sees a linear timeline.
        assertThat(h.output.offeredNextFrames).isEqualTo(h.output.acceptedBeforeOffer)
    }

    // --- Loop ------------------------------------------------------------------------------------------------------

    @Test
    fun `a loop set ahead plays live to Out then repeats from memory with an exact period`() {
        val h = EngineHarness(totalFrames = 20_000)
        val n = blendLength(3_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))

        h.runTo(5_000L + 3 * 3_000)

        // Live up to Out.
        for (i in 0 until 5_000L) assertThat(h.sourceAt(i)).isEqualTo(i.toInt())
        // The first repeat: blend of the post-roll and the start, then the loop's own audio.
        for (k in 0 until n) assertThat(h.sourceAt(5_000L + k)).isEqualTo(h.blendOf(5_000 + k, 2_000 + k, k, n))
        for (k in n until 3_000) assertThat(h.sourceAt(5_000L + k)).isEqualTo(2_000 + k)
        // Sample-exact period (both channels).
        assertThat(h.output.bytes(5_000, 3_000)).isEqualTo(h.output.bytes(8_000, 3_000))
        // The decoder stalled: only the post-roll was taken beyond Out.
        assertThat(h.consumedFrames).isEqualTo(5_000L + n)
        assertThat(h.events).containsExactly(LoopEvent.Engaged(LoopFrames(2_000, 5_000)))
        assertThat(h.engine.status).isEqualTo(LoopStatus(LoopState.LOOPING, LoopFrames(2_000, 5_000), false))
    }

    @Test
    fun `the signal is continuous at the entry and at every wrap`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))

        h.runTo(5_000L + 3 * 3_000)

        for (wrap in listOf(5_000L, 8_000L, 11_000L)) {
            // x[Out - 1] then x[Out]: the counter goes on by one.
            assertThat(h.sourceAt(wrap - 1)).isEqualTo(4_999)
            assertThat(h.sourceAt(wrap)).isEqualTo(5_000)
        }
    }

    @Test
    fun `the timeline maps the loop back to the source and counts the wraps`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))

        h.runTo(5_000L + 3 * 3_000)

        val timeline = h.engine.timeline
        assertThat(timeline.sourceFrameAt(4_999)).isEqualTo(4_999L)
        assertThat(timeline.sourceFrameAt(5_000)).isEqualTo(2_000L)
        assertThat(timeline.sourceFrameAt(8_007)).isEqualTo(2_007L)
        assertThat(timeline.wrapsAt(4_999)).isEqualTo(0L)
        assertThat(timeline.wrapsAt(5_000)).isEqualTo(1L)
        assertThat(timeline.wrapsAt(7_999)).isEqualTo(1L)
        assertThat(timeline.wrapsAt(8_000)).isEqualTo(2L)
    }

    @Test
    fun `a loop set late joins from the frontier with the same blend`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.runTo(5_000)
        val frontier = h.consumedFrames.toInt()
        val n = blendLength(1_000)

        h.engine.submit(LoopCommand.Set(LoopFrames(1_000, 2_000)))
        h.runTo(frontier + 4_000L)

        // Everything the engine had taken plays live, then the join: post-roll fades out against the start.
        for (i in 0 until frontier.toLong()) assertThat(h.sourceAt(i)).isEqualTo(i.toInt())
        for (k in 0 until n) assertThat(h.sourceAt(frontier + k.toLong())).isEqualTo(h.blendOf(frontier + k, 1_000 + k, k, n))
        for (k in n until 1_000) assertThat(h.sourceAt(frontier + k.toLong())).isEqualTo(1_000 + k)
        // Later repeats use the audio after Out as post-roll, and are periodic.
        for (k in 0 until n) assertThat(h.sourceAt(frontier + 1_000L + k)).isEqualTo(h.blendOf(2_000 + k, 1_000 + k, k, n))
        assertThat(h.output.bytes(frontier + 1_000L, 1_000)).isEqualTo(h.output.bytes(frontier + 2_000L, 1_000))
        assertThat(h.consumedFrames).isEqualTo(frontier + n.toLong())
    }

    @Test
    fun `a sine loops without a click in every encoding`() {
        for (encoding in PcmSamples.ALL_ENCODINGS) {
            val format = PcmFormat(rate, 2, encoding)
            val amplitude = 0.5
            val source = PcmSamples.signal(format, 30_000) { amplitude * sin(2 * PI * 440.0 * it / rate) }
            val h = EngineHarness(totalFrames = 30_000, format = format, source = source)
            h.engine.submit(LoopCommand.Set(LoopFrames(5_000, 17_345)))

            h.runTo(17_345L + 4 * 12_345)

            var worst = 0.0
            for (i in 1 until 17_345L + 4 * 12_345) {
                val now = PcmSamples.read(format, h.output.bytes(i, 1), 0, 0)
                val before = PcmSamples.read(format, h.output.bytes(i - 1, 1), 0, 0)
                worst = maxOf(worst, abs(now - before))
            }
            val natural = amplitude * 2 * PI * 440.0 / rate
            assertThat(worst).isLessThan(natural * 1.6)
            // The period is exact in every encoding.
            assertThat(h.output.bytes(17_345, 12_345)).isEqualTo(h.output.bytes(17_345L + 12_345, 12_345))
        }
    }

    // --- Exit ------------------------------------------------------------------------------------------------------

    @Test
    fun `exit mid loop plays on linearly from where the playhead is, sample identical to the original stream`() {
        val h = EngineHarness(totalFrames = 40_000)
        val n = blendLength(3_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000 + 1_500)
        val exitAt = h.engine.outputFrames
        val position = h.engine.timeline.sourceFrameAt(exitAt)
        assertThat(position - 2_000).isAtLeast(n.toLong())

        h.engine.submit(LoopCommand.Exit)
        h.runTo(exitAt + 12_000)

        // No blend, no jump, no skip: p, p + 1, ... through Out, the captured tail and the live input.
        for (j in 0 until 12_000L) {
            assertThat(h.sourceAt(exitAt + j)).isEqualTo((position + j).toInt())
            assertThat(h.output.intAt(exitAt + j, channel = 1)).isEqualTo(-(position + j).toInt())
        }
        // The timeline is linear after the exit and the exit is not a wrap.
        for (j in 0 until 12_000L) assertThat(h.engine.timeline.sourceFrameAt(exitAt + j)).isEqualTo(position + j)
        assertThat(h.engine.timeline.wrapsAt(exitAt + 11_999)).isEqualTo(h.engine.timeline.wrapsAt(exitAt - 1))
        assertThat(h.engine.timeline.isLoopingAt(exitAt + 5)).isFalse()
        assertThat(h.events.last()).isEqualTo(LoopEvent.Released(LoopFrames(2_000, 5_000)))
        assertThat(h.engine.status).isEqualTo(LoopStatus(LoopState.OFF, LoopFrames(2_000, 5_000), false))
    }

    @Test
    fun `exit inside the blend finishes the blend and then goes on linearly`() {
        val h = EngineHarness(totalFrames = 40_000, stagingFrames = 64, chunkFrames = 32)
        val n = blendLength(3_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000 + 40)
        val exitAt = h.engine.outputFrames
        val phase = ((exitAt - 5_000) % 3_000).toInt()
        assertThat(phase).isLessThan(n)

        h.engine.submit(LoopCommand.Exit)
        h.runTo(exitAt + 6_000)

        for (j in 0 until 6_000) {
            val position = phase + j
            val expected = if (position < n) h.blendOf(5_000 + position, 2_000 + position, position, n) else 2_000 + position
            assertThat(h.sourceAt(exitAt + j)).isEqualTo(expected)
        }
    }

    @Test
    fun `reloop after an exit joins again from memory`() {
        val h = EngineHarness(totalFrames = 60_000)
        val n = blendLength(3_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000 + 1_500)
        val exitAt = h.engine.outputFrames
        val position = h.engine.timeline.sourceFrameAt(exitAt).toInt()
        h.engine.submit(LoopCommand.Exit)
        h.runTo(exitAt + 12_000)
        val frontier = h.consumedFrames.toInt()

        h.engine.submit(LoopCommand.Reloop)
        h.runTo(exitAt + 12_000L + 8_000)

        // The audio the engine had taken plays out; then the loop joins from the frontier.
        val joinAt = exitAt + (frontier - position)
        assertThat(h.engine.status.state).isEqualTo(LoopState.LOOPING)
        for (k in 0 until n) assertThat(h.sourceAt(joinAt + k)).isEqualTo(h.blendOf(frontier + k, 2_000 + k, k, n))
        for (k in n until 3_000) assertThat(h.sourceAt(joinAt + k)).isEqualTo(2_000 + k)
        assertThat(h.consumedFrames).isEqualTo(frontier + n.toLong())
        assertThat(h.events.filterIsInstance<LoopEvent.Engaged>()).hasSize(2)
    }

    @Test
    fun `exit before the loop engaged disarms it and keeps it for reloop`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.engine.submit(LoopCommand.Exit)

        h.runToEnd()

        assertThat(h.output.totalFrames).isEqualTo(20_000L)
        for (i in 0 until 20_000L step 997) assertThat(h.sourceAt(i)).isEqualTo(i.toInt())
        assertThat(h.engine.status).isEqualTo(LoopStatus(LoopState.OFF, LoopFrames(2_000, 5_000), false))
    }

    @Test
    fun `setting another loop while looping leaves the first linearly and arms the new one`() {
        val h = EngineHarness(totalFrames = 60_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000 + 1_500)
        val exitAt = h.engine.outputFrames
        val position = h.engine.timeline.sourceFrameAt(exitAt).toInt()

        h.engine.submit(LoopCommand.Set(LoopFrames(10_000, 11_000)))
        h.runTo(exitAt + 20_000)

        // Linear from p up to the new Out, then the new loop.
        for (j in 0 until (11_000 - position)) assertThat(h.sourceAt(exitAt + j)).isEqualTo(position + j)
        assertThat(h.engine.status).isEqualTo(LoopStatus(LoopState.LOOPING, LoopFrames(10_000, 11_000), false))
        assertThat(h.engine.timeline.sourceFrameAt(exitAt + 20_000 - 1)).isAtLeast(10_000L)
    }

    // --- Memory and fallback ---------------------------------------------------------------------------------------

    @Test
    fun `a loop longer than the memory cap is rejected for the seek fallback`() {
        val h = EngineHarness(totalFrames = 20_000, memoryCapBytes = 8_000L * 8)

        h.engine.submit(LoopCommand.Set(LoopFrames(0, 8_000)))
        h.runTo(100)

        assertThat(h.events).containsExactly(LoopEvent.Rejected(LoopRejection.TOO_LONG))
        assertThat(h.engine.status.state).isEqualTo(LoopState.OFF)
    }

    @Test
    fun `a loop shorter than five milliseconds is rejected`() {
        val h = EngineHarness(totalFrames = 2_000)

        h.engine.submit(LoopCommand.Set(LoopFrames(100, 110)))
        h.runTo(100)

        assertThat(h.events).containsExactly(LoopEvent.Rejected(LoopRejection.TOO_SHORT))
    }

    @Test
    fun `when In is no longer in memory the engine asks for a seek and a seek makes the loop work`() {
        val h = EngineHarness(totalFrames = 20_000, memoryCapBytes = 8_000L * 8)
        h.runToEnd()

        h.engine.submit(LoopCommand.Set(LoopFrames(1_000, 1_500)))
        h.engine.pump()

        assertThat(h.events).containsExactly(LoopEvent.NeedsSeek(LoopFrames(1_000, 1_500)))
        assertThat(h.engine.status.seekNeeded).isTrue()

        // The controller seeks to In: the armed loop captures from there and wraps on its own.
        h.seekTo(1_000)
        val start = h.output.totalFrames
        h.runTo(start + 500 + 3 * 500)

        for (i in 0 until 500L) assertThat(h.sourceAt(start + i)).isEqualTo(1_000 + i.toInt())
        assertThat(h.output.bytes(start + 500, 500)).isEqualTo(h.output.bytes(start + 1_000, 500))
        assertThat(h.events.filterIsInstance<LoopEvent.Engaged>()).hasSize(1)
    }

    // --- Flush, clear, end of stream ------------------------------------------------------------------------------

    @Test
    fun `a flush clears the captured audio and keeps the armed loop`() {
        val h = EngineHarness(totalFrames = 40_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000)

        h.seekTo(1_000)

        assertThat(h.engine.status.state).isEqualTo(LoopState.ARMED)
        assertThat(h.engine.outputFrames).isEqualTo(0L)
        val start = h.output.totalFrames
        h.runTo(start + 4_000L + 6_000)
        for (i in 0 until 4_000L) assertThat(h.sourceAt(start + i)).isEqualTo(1_000 + i.toInt())
        assertThat(h.output.bytes(start + 4_000, 3_000)).isEqualTo(h.output.bytes(start + 7_000, 3_000))
        assertThat(h.engine.timeline.sourceFrameAt(0)).isEqualTo(1_000L)
    }

    @Test
    fun `a flush after the loop leaves nothing to loop from`() {
        val h = EngineHarness(totalFrames = 40_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000)

        h.seekTo(9_000)
        h.runTo(h.output.totalFrames + 2_000)

        assertThat(h.events.filterIsInstance<LoopEvent.NeedsSeek>()).hasSize(1)
        assertThat(h.engine.status.state).isEqualTo(LoopState.ARMED)
    }

    @Test
    fun `clear forgets the loop for a new track`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(7_000)

        h.engine.clear()
        h.seekTo(0)
        h.runToEnd()

        assertThat(h.engine.status).isEqualTo(LoopStatus.OFF)
        for (i in 0 until 20_000L step 991) assertThat(h.sourceAt(h.output.totalFrames - 20_000 + i)).isEqualTo(i.toInt())
    }

    @Test
    fun `a presentation time that jumps re-anchors the source and counts as a wrap`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.limit = 1_000
        h.runToEnd()

        h.jumpTo(5_000, 6_000)
        h.runToEnd()

        assertThat(h.sourceAt(999)).isEqualTo(999)
        assertThat(h.sourceAt(1_000)).isEqualTo(5_000)
        assertThat(h.engine.timeline.sourceFrameAt(1_000)).isEqualTo(5_000L)
        assertThat(h.engine.timeline.wrapsAt(1_500)).isEqualTo(1L)
    }

    @Test
    fun `a loop near the end of the track keeps looping with the post-roll there is`() {
        val h = EngineHarness(totalFrames = 5_100)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_100)

        h.engine.endOfInput()
        h.runTo(5_100L + 7_000)

        assertThat(h.engine.status.state).isEqualTo(LoopState.LOOPING)
        assertThat(h.engine.isEnded()).isFalse()
        // 100 frames of post-roll: the join blends 100 frames, then the audio of the loop to Out.
        for (k in 100 until 3_000 - 100) assertThat(h.sourceAt(5_000L + k)).isEqualTo(2_000 + k)
        // Later repeats blend those 100 frames of post-roll.
        for (k in 0 until 100) assertThat(h.sourceAt(8_000L + k)).isEqualTo(h.blendOf(5_000 + k, 2_000 + k, k, 100))
    }

    @Test
    fun `without a loop the stream ends when the audio is out`() {
        val h = EngineHarness(totalFrames = 3_000)
        h.runToEnd()

        h.engine.endOfInput()
        h.engine.pump()

        assertThat(h.engine.isEnded()).isTrue()
        assertThat(h.engine.hasPendingData()).isFalse()
    }

    @Test
    fun `reloop without a remembered loop is rejected`() {
        val h = EngineHarness(totalFrames = 1_000)

        h.engine.submit(LoopCommand.Reloop)
        h.runTo(100)

        assertThat(h.events).containsExactly(LoopEvent.Rejected(LoopRejection.NO_LOOP))
    }
}
