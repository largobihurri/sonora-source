package io.sonora.core.djloop

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.loop.LoopFrames
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import org.junit.Test

class LoopEngineResizeTest {

    private val rate = 48_000

    private fun blendLength(loopFrames: Int) = LoopBlend.blendFrames(rate, loopFrames.toLong())

    /** A harness with a small staging block, so the engine runs only a few frames ahead of what the test has seen. */
    private fun tightHarness(total: Int = 40_000, cap: Long = LoopEngine.DEFAULT_MEMORY_CAP_BYTES) =
        EngineHarness(totalFrames = total, memoryCapBytes = cap, stagingFrames = 64, chunkFrames = 32)

    @Test
    fun `halving while the playhead is before the new Out shortens the loop at the next wrap`() {
        val h = tightHarness()
        val n = blendLength(3_000)
        val shorter = blendLength(1_500)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 500)
        val at = h.engine.outputFrames
        val phase = ((at - 5_000) % 3_000).toInt()
        assertThat(phase).isIn(n until 1_500)

        h.engine.submit(LoopCommand.Halve)
        h.runTo(at + 1_500 + 3_000)

        // The iteration goes on as it was up to the new Out, where the loop wraps.
        val wrap = 1_500 - phase
        for (j in 0 until wrap) assertThat(h.sourceAt(at + j)).isEqualTo(2_000 + phase + j)
        for (i in 0 until shorter) assertThat(h.sourceAt(at + wrap + i)).isEqualTo(h.blendOf(3_500 + i, 2_000 + i, i, shorter))
        for (i in shorter until 1_500) assertThat(h.sourceAt(at + wrap + i)).isEqualTo(2_000 + i)
        assertThat(h.output.bytes(at + wrap, 1_500)).isEqualTo(h.output.bytes(at + wrap + 1_500, 1_500))
        assertThat(h.engine.status.loop).isEqualTo(LoopFrames(2_000, 3_500))
    }

    @Test
    fun `halving while the playhead is beyond the new Out wraps at once through the blend`() {
        val h = tightHarness()
        val shorter = blendLength(1_500)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 2_000)
        val at = h.engine.outputFrames
        val phase = ((at - 5_000) % 3_000).toInt()
        assertThat(phase).isAtLeast(1_500)

        h.engine.submit(LoopCommand.Halve)
        h.runTo(at + 3 * 1_500)

        // The audio goes on from the playhead and fades into the start of the shorter loop.
        for (i in 0 until shorter) {
            assertThat(h.sourceAt(at + i)).isEqualTo(h.blendOf(2_000 + phase + i, 2_000 + i, i, shorter))
        }
        for (i in shorter until 1_500) assertThat(h.sourceAt(at + i)).isEqualTo(2_000 + i)
        assertThat(h.output.bytes(at + 1_500, 1_500)).isEqualTo(h.output.bytes(at + 3_000, 1_500))
        // The jump back is a wrap for the position clock.
        assertThat(h.engine.timeline.wrapsAt(at)).isEqualTo(h.engine.timeline.wrapsAt(at - 1) + 1)
    }

    @Test
    fun `halving inside the blend waits for the blend to end`() {
        val h = tightHarness()
        val n = blendLength(3_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000 + 40)
        val at = h.engine.outputFrames
        val phase = ((at - 5_000) % 3_000).toInt()
        assertThat(phase).isLessThan(n)

        h.engine.submit(LoopCommand.Halve)
        h.runTo(at + 2_000)

        for (j in 0 until 1_000) {
            val position = phase + j
            val expected = if (position < n) h.blendOf(5_000 + position, 2_000 + position, position, n) else 2_000 + position
            assertThat(h.sourceAt(at + j)).isEqualTo(expected)
        }
        assertThat(h.engine.status.loop).isEqualTo(LoopFrames(2_000, 3_500))
    }

    @Test
    fun `halving a loop that is armed moves Out before it engages`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.engine.submit(LoopCommand.Halve)

        h.runTo(3_500L + 3_000)

        for (i in 0 until 3_500L) assertThat(h.sourceAt(i)).isEqualTo(i.toInt())
        assertThat(h.output.bytes(3_500, 1_500)).isEqualTo(h.output.bytes(5_000, 1_500))
        assertThat(h.consumedFrames).isEqualTo(3_500L + blendLength(1_500))
    }

    @Test
    fun `a loop cannot be halved below the minimum`() {
        val h = EngineHarness(totalFrames = 20_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 2_300)))

        h.engine.submit(LoopCommand.Halve)
        h.runTo(100)

        assertThat(h.events).containsExactly(LoopEvent.Rejected(LoopRejection.TOO_SHORT))
        assertThat(h.engine.status.loop).isEqualTo(LoopFrames(2_000, 2_300))
    }

    @Test
    fun `doubling while looping plays on past Out, captures it, and wraps at the new Out`() {
        val h = EngineHarness(totalFrames = 60_000)
        val n = blendLength(6_000)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000)))
        h.runTo(5_000L + 3_000 + 1_000)
        val at = h.engine.outputFrames
        val position = h.engine.timeline.sourceFrameAt(at).toInt()

        h.engine.submit(LoopCommand.Double)
        h.runTo(at + 8_000L + 3 * 6_000)

        // Linear from the playhead through Out, the captured tail and live input to the new Out (8000).
        for (j in 0 until 8_000 - position) assertThat(h.sourceAt(at + j)).isEqualTo(position + j)
        val loopStart = at + (8_000 - position)
        // Then the loop of 6000 frames: blend of the live post-roll, the audio from In, an exact period.
        for (k in 0 until n) assertThat(h.sourceAt(loopStart + k)).isEqualTo(h.blendOf(8_000 + k, 2_000 + k, k, n))
        for (k in n until 6_000) assertThat(h.sourceAt(loopStart + k)).isEqualTo(2_000 + k)
        assertThat(h.output.bytes(loopStart + 6_000, 6_000)).isEqualTo(h.output.bytes(loopStart + 12_000, 6_000))
        assertThat(h.consumedFrames).isEqualTo(8_000L + n)
        assertThat(h.engine.status.loop).isEqualTo(LoopFrames(2_000, 8_000))
    }

    @Test
    fun `a loop cannot be doubled past the memory cap`() {
        val h = EngineHarness(totalFrames = 20_000, memoryCapBytes = 8_000L * 8)
        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 6_000)))

        h.engine.submit(LoopCommand.Double)
        h.runTo(100)

        assertThat(h.events).containsExactly(LoopEvent.Rejected(LoopRejection.TOO_LONG))
        assertThat(h.engine.status.loop).isEqualTo(LoopFrames(2_000, 6_000))
    }

    @Test
    fun `halve and double need a loop`() {
        val h = EngineHarness(totalFrames = 1_000)

        h.engine.submit(LoopCommand.Halve)
        h.engine.submit(LoopCommand.Double)
        h.runTo(100)

        assertThat(h.events).containsExactly(
            LoopEvent.Rejected(LoopRejection.NO_LOOP),
            LoopEvent.Rejected(LoopRejection.NO_LOOP),
        )
    }

    @Test
    fun `commands sent from another thread take effect on the playback thread`() {
        val h = EngineHarness(totalFrames = 20_000)

        val sender = Thread { h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 5_000))) }
        sender.start()
        sender.join()
        h.runTo(5_000L + 3_000)

        assertThat(h.engine.status.state).isEqualTo(LoopState.LOOPING)
        assertThat(h.events).containsExactly(LoopEvent.Engaged(LoopFrames(2_000, 5_000)))
    }

    @Test
    fun `the per buffer path does not allocate, passing audio through or looping`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val format = PcmFormat(rate, 2, PcmEncoding.PCM_16)
        val sink = LoopOutput { buffer, _ -> buffer.position(buffer.limit()); true }
        val engine = LoopEngine(format, sink)
        val input = ByteBuffer.wrap(ByteArray(480 * format.frameBytes))
        var frame = 0L

        fun writeOnce() {
            input.clear()
            engine.write(input, frame * 1_000_000 / rate)
            if (!input.hasRemaining()) frame += 480
        }
        engine.submit(LoopCommand.Set(LoopFrames(10_000, 20_000)))
        repeat(2_000) { writeOnce() } // warm up: engages the loop (allocating its status and event) and JITs
        val me = Thread.currentThread().id

        val before = bean.getThreadAllocatedBytes(me)
        repeat(2_000) { writeOnce() }
        val allocated = bean.getThreadAllocatedBytes(me) - before

        assertThat(engine.status.state).isEqualTo(LoopState.LOOPING)
        assertThat(allocated).isLessThan(2_048L)

        val passing = LoopEngine(format, sink)
        var passingFrame = 0L
        fun passOnce() {
            input.clear()
            passing.write(input, passingFrame * 1_000_000 / rate)
            passingFrame += 480
        }
        repeat(2_000) { passOnce() }
        val passBefore = bean.getThreadAllocatedBytes(me)
        repeat(2_000) { passOnce() }
        assertThat(bean.getThreadAllocatedBytes(me) - passBefore).isLessThan(2_048L)
    }
}
