package io.sonora.core.djloop

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.loop.LoopFrames
import org.junit.Test

/** [LoopCommand.Reserve]: capture memory kept for a loop whose In was marked a while before its Out. */
class LoopEngineReserveTest {

    private val rate = 48_000

    @Test
    fun `an In marked long before Out is still in memory when a reserve was asked for`() {
        // 10 s between In and Out; the default capture is about 2 s.
        val h = EngineHarness(totalFrames = 600_000)
        h.runTo(2_000)
        h.engine.submit(LoopCommand.Reserve(12L * rate))
        h.runTo(500_000)

        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 482_000)))
        h.runTo(500_000L + 10_000)

        assertThat(h.events.filterIsInstance<LoopEvent.NeedsSeek>()).isEmpty()
        assertThat(h.events.filterIsInstance<LoopEvent.Engaged>()).hasSize(1)
        assertThat(h.engine.status.state).isEqualTo(LoopState.LOOPING)
    }

    @Test
    fun `without a reserve the same In has gone from memory and the engine asks for a seek`() {
        val h = EngineHarness(totalFrames = 600_000)
        h.runTo(500_000)

        h.engine.submit(LoopCommand.Set(LoopFrames(2_000, 482_000)))
        h.runTo(500_000L + 10_000)

        assertThat(h.events.filterIsInstance<LoopEvent.NeedsSeek>()).hasSize(1)
    }

    @Test
    fun `a reserve keeps the audio already captured`() {
        val h = EngineHarness(totalFrames = 100_000)
        h.runTo(40_000)

        h.engine.submit(LoopCommand.Reserve(300_000))
        h.runTo(45_000)
        val frontier = h.consumedFrames
        h.engine.submit(LoopCommand.Set(LoopFrames(1_000, 3_000)))
        h.runTo(frontier + 6_000)

        // The loop's start was captured before the reserve and is repeated from the grown memory (it joins at the frontier).
        val n = LoopBlend.blendFrames(rate, 2_000)
        for (k in n until 2_000) assertThat(h.sourceAt(frontier + k)).isEqualTo(1_000 + k)
        assertThat(h.events.filterIsInstance<LoopEvent.NeedsSeek>()).isEmpty()
    }

    @Test
    fun `a reserve is capped by the memory cap`() {
        // 1 MiB of 32-bit stereo is 131072 frames.
        val h = EngineHarness(totalFrames = 400_000, memoryCapBytes = 1L shl 20)
        h.runTo(1_000)

        h.engine.submit(LoopCommand.Reserve(Long.MAX_VALUE))
        h.runTo(300_000)

        // Nothing broke and the audio still comes out in order.
        for (i in 0 until 300_000L step 7_919) assertThat(h.sourceAt(i)).isEqualTo(i.toInt())
        h.engine.submit(LoopCommand.Set(LoopFrames(1_000, 3_000)))
        h.runTo(310_000)
        assertThat(h.events.filterIsInstance<LoopEvent.NeedsSeek>()).hasSize(1)
    }
}
