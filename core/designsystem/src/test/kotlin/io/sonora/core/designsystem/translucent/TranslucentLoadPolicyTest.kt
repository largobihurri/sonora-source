package io.sonora.core.designsystem.translucent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranslucentLoadPolicyTest {

    /** 90 Hz, the OnePlus 7 Pro's rate. */
    private val budget = 1000.0 / 90

    /** The rule of the last step down while feeding [count] frames, or null when none fired. */
    private fun TranslucentLoadPolicy.feed(count: Int, gpuMs: Double, budgetMs: Double = budget): StepDownRule? {
        var stepDown: StepDownRule? = null
        repeat(count) { frame(gpuMs, budgetMs)?.let { stepDown = it } }
        return stepDown
    }

    private fun settled() = TranslucentLoadPolicy().also { it.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 1.0) }

    @Test
    fun `frames within budget never step down`() {
        val policy = TranslucentLoadPolicy()

        assertThat(policy.feed(5_000, gpuMs = 6.0)).isNull()
    }

    @Test
    fun `a few frames that stall the GPU step down`() {
        val policy = settled()

        assertThat(policy.feed(TranslucentLoadPolicy.SEVERE_FRAMES - 1, gpuMs = 4_950.0)).isNull()
        assertThat(policy.frame(gpuMs = 4_950.0, budgetMs = budget)).isEqualTo(StepDownRule.SEVERE)
    }

    @Test
    fun `stalls far apart do not add up`() {
        val policy = settled()

        repeat(10) {
            assertThat(policy.frame(gpuMs = 900.0, budgetMs = budget)).isNull()
            assertThat(policy.feed(TranslucentLoadPolicy.WINDOW_FRAMES, gpuMs = 5.0)).isNull()
        }
    }

    @Test
    fun `the first frames after a start are ignored while layers are recorded`() {
        val policy = TranslucentLoadPolicy()

        assertThat(policy.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 4_950.0)).isNull()
    }

    @Test
    fun `a sustained share of heavy frames steps down`() {
        val heavy = (TranslucentLoadPolicy.WINDOW_FRAMES * TranslucentLoadPolicy.HEAVY_SHARE).toInt()
        val policy = settled()

        // Spread evenly: one heavy frame every few, 60 ms each (well past twice the budget, below a stall).
        var stepDown: StepDownRule? = null
        val every = TranslucentLoadPolicy.WINDOW_FRAMES / heavy
        repeat(TranslucentLoadPolicy.WINDOW_FRAMES) { i ->
            policy.frame(if (i % every == 0) 60.0 else 5.0, budget)?.let { stepDown = it }
        }

        assertThat(stepDown).isEqualTo(StepDownRule.HEAVY)
    }

    @Test
    fun `heavy frames below the share do not step down`() {
        val policy = settled()

        var stepDown: StepDownRule? = null
        repeat(TranslucentLoadPolicy.WINDOW_FRAMES * 10) { i ->
            policy.frame(if (i % 5 == 0) 60.0 else 5.0, budget)?.let { stepDown = it }
        }

        assertThat(stepDown).isNull()
    }

    @Test
    fun `a 144 Hz window of animation frames at 24 to 30 ms does not step down`() {
        // The Pad 6 opening the equalizer: past twice its 6.9 ms budget, but no 60 Hz frame missed twice.
        val policy = settled()

        var stepDown: StepDownRule? = null
        repeat(TranslucentLoadPolicy.WINDOW_FRAMES * 10) { i ->
            policy.frame(gpuMs = 24.0 + i % 7, budgetMs = 1000.0 / 144)?.let { stepDown = it }
        }

        assertThat(stepDown).isNull()
    }

    @Test
    fun `heavy means two missed 60 Hz frames and twice the frame budget`() {
        // 120 Hz and 90 Hz: past twice the budget, but under two 60 Hz frames (33.3 ms).
        assertThat(settled().feed(1_000, gpuMs = 18.0, budgetMs = 1000.0 / 120)).isNull()
        assertThat(settled().feed(1_000, gpuMs = 25.0, budgetMs = budget)).isNull()
        assertThat(settled().feed(1_000, gpuMs = 33.0, budgetMs = 1000.0 / 144)).isNull()
        // 60 Hz: 40 ms is past both.
        assertThat(settled().feed(1_000, gpuMs = 40.0, budgetMs = 1000.0 / 60)).isEqualTo(StepDownRule.HEAVY)
        // 30 Hz: 50 ms is past two 60 Hz frames, but not twice the 33.3 ms budget.
        assertThat(settled().feed(1_000, gpuMs = 50.0, budgetMs = 1000.0 / 30)).isNull()
    }

    @Test
    fun `a stall on a fast screen still steps down as severe`() {
        val policy = settled()

        assertThat(policy.feed(TranslucentLoadPolicy.SEVERE_FRAMES, gpuMs = 4_950.0, budgetMs = 1000.0 / 144))
            .isEqualTo(StepDownRule.SEVERE)
    }

    @Test
    fun `after a step down it needs fresh evidence at the new tier`() {
        val policy = settled()
        assertThat(policy.feed(TranslucentLoadPolicy.SEVERE_FRAMES, gpuMs = 4_950.0)).isEqualTo(StepDownRule.SEVERE)

        // The next frames belong to the lower tier: settling again, then judged on their own.
        assertThat(policy.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 4_950.0)).isNull()
        assertThat(policy.feed(TranslucentLoadPolicy.WINDOW_FRAMES, gpuMs = 6.0)).isNull()
    }

    @Test
    fun `a restart forgets what was seen`() {
        val policy = settled()
        policy.feed(TranslucentLoadPolicy.SEVERE_FRAMES - 1, gpuMs = 4_950.0)

        policy.restart()
        policy.feed(TranslucentLoadPolicy.SETTLE_FRAMES, gpuMs = 1.0)

        assertThat(policy.frame(gpuMs = 4_950.0, budgetMs = budget)).isNull()
    }
}
