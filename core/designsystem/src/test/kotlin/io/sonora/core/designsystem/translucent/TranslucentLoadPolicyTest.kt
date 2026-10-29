package io.sonora.core.designsystem.translucent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentLoadPolicyTest {

    /** 90 Hz, the OnePlus 7 Pro's rate. */
    private val budget = 1000.0 / 90

    private fun TranslucentLoadPolicy.feed(count: Int, gpuMs: Double, budgetMs: Double = budget): Boolean {
        var stepDown = false
        repeat(count) { if (frame(gpuMs, budgetMs)) stepDown = true }
        return stepDown
    }

    private fun settled() = TranslucentLoadPolicy().also { it.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 1.0) }

    @Test
    fun `frames within budget never step down`() {
        val policy = TranslucentLoadPolicy()

        assertThat(policy.feed(5_000, gpuMs = 6.0)).isFalse()
    }

    @Test
    fun `a few frames that stall the GPU step down`() {
        val policy = settled()

        assertThat(policy.feed(TranslucentLoadPolicy.SEVERE_FRAMES - 1, gpuMs = 4_950.0)).isFalse()
        assertThat(policy.frame(gpuMs = 4_950.0, budgetMs = budget)).isTrue()
    }

    @Test
    fun `stalls far apart do not add up`() {
        val policy = settled()

        repeat(10) {
            assertThat(policy.frame(gpuMs = 900.0, budgetMs = budget)).isFalse()
            assertThat(policy.feed(TranslucentLoadPolicy.WINDOW_FRAMES, gpuMs = 5.0)).isFalse()
        }
    }

    @Test
    fun `the first frames after a start are ignored while layers are recorded`() {
        val policy = TranslucentLoadPolicy()

        assertThat(policy.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 4_950.0)).isFalse()
    }

    @Test
    fun `a sustained share of heavy frames steps down`() {
        val heavy = (TranslucentLoadPolicy.WINDOW_FRAMES * TranslucentLoadPolicy.HEAVY_SHARE).toInt()
        val policy = settled()

        // Spread evenly: one heavy frame every few, 60 ms each (well past twice the budget, below a stall).
        var stepDown = false
        val every = TranslucentLoadPolicy.WINDOW_FRAMES / heavy
        repeat(TranslucentLoadPolicy.WINDOW_FRAMES) { i ->
            if (policy.frame(if (i % every == 0) 60.0 else 5.0, budget)) stepDown = true
        }

        assertThat(stepDown).isTrue()
    }

    @Test
    fun `heavy frames below the share do not step down`() {
        val policy = settled()

        var stepDown = false
        repeat(TranslucentLoadPolicy.WINDOW_FRAMES * 10) { i ->
            if (policy.frame(if (i % 5 == 0) 60.0 else 5.0, budget)) stepDown = true
        }

        assertThat(stepDown).isFalse()
    }

    @Test
    fun `heavy means twice the frame budget, never under the floor`() {
        // 120 Hz: twice the budget is 16.7 ms, under the 20 ms floor, so 18 ms frames are not heavy.
        assertThat(settled().feed(1_000, gpuMs = 18.0, budgetMs = 1000.0 / 120)).isFalse()
        // 60 Hz: twice the budget is 33.3 ms, so 25 ms frames are not heavy either.
        assertThat(settled().feed(1_000, gpuMs = 25.0, budgetMs = 1000.0 / 60)).isFalse()
        // 90 Hz: 25 ms is past twice the budget (22.2 ms) and the floor.
        assertThat(settled().feed(1_000, gpuMs = 25.0, budgetMs = budget)).isTrue()
    }

    @Test
    fun `after a step down it needs fresh evidence at the new tier`() {
        val policy = settled()
        assertThat(policy.feed(TranslucentLoadPolicy.SEVERE_FRAMES, gpuMs = 4_950.0)).isTrue()

        // The next frames belong to the lower tier: settling again, then judged on their own.
        assertThat(policy.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 4_950.0)).isFalse()
        assertThat(policy.feed(TranslucentLoadPolicy.WINDOW_FRAMES, gpuMs = 6.0)).isFalse()
    }

    @Test
    fun `a restart forgets what was seen`() {
        val policy = settled()
        policy.feed(TranslucentLoadPolicy.SEVERE_FRAMES - 1, gpuMs = 4_950.0)

        policy.restart()
        policy.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 1.0)

        assertThat(policy.frame(gpuMs = 4_950.0, budgetMs = budget)).isFalse()
    }
}
