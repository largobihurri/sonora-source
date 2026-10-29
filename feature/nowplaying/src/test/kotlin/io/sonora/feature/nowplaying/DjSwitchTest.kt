package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/*
 * The DJ on/off transition (D2.10): which arrangements are composed while Now Playing swaps the standard layout for the
 * DJ one (SwitchPlan), and how each is drawn at a point of the 300 ms swap (switchFrame).
 */
class DjSwitchTest {

    private val steps = (0..100).map { it / 100f }

    // --- the plan: which arrangements stay composed ---

    @Test
    fun `a switch starts with one settled layer`() {
        val plan = SwitchPlan.of(value = "standard", key = false)

        assertThat(plan.layers).hasSize(1)
        assertThat(plan.transitioning).isFalse()
        assertThat(plan.roleOf(plan.target)).isEqualTo(SwitchRole.Settled)
    }

    @Test
    fun `a new key adds an incoming layer over the outgoing one`() {
        val plan = SwitchPlan.of("standard", key = false).retarget("dj", key = true)

        assertThat(plan.layers.map { it.value }).containsExactly("standard", "dj").inOrder()
        assertThat(plan.transitioning).isTrue()
        assertThat(plan.roleOf(plan.layers[0])).isEqualTo(SwitchRole.Outgoing)
        assertThat(plan.roleOf(plan.layers[1])).isEqualTo(SwitchRole.Incoming)
        assertThat(plan.target.value).isEqualTo("dj")
    }

    @Test
    fun `the same key updates the layer in place and starts nothing`() {
        val first = SwitchPlan.of("a", key = false)
        val second = first.retarget("b", key = false)

        assertThat(second.layers).hasSize(1)
        assertThat(second.target.value).isEqualTo("b")
        assertThat(second.target.id).isEqualTo(first.target.id)
        assertThat(second.transitioning).isFalse()
    }

    @Test
    fun `retargeting to what is already there changes nothing`() {
        val plan = SwitchPlan.of("a", key = false).retarget("b", key = true)

        assertThat(plan.retarget("b", key = true)).isSameInstanceAs(plan)
    }

    @Test
    fun `an outgoing layer keeps the value it had when it left`() {
        val plan = SwitchPlan.of("standard 1", key = false).retarget("dj", key = true).retarget("dj 2", key = true)

        assertThat(plan.layers.map { it.value }).containsExactly("standard 1", "dj 2").inOrder()
    }

    @Test
    fun `settling drops the outgoing layer and keeps the target's identity`() {
        val moving = SwitchPlan.of("standard", key = false).retarget("dj", key = true)
        val settled = moving.settled()

        assertThat(settled.layers).hasSize(1)
        assertThat(settled.target.id).isEqualTo(moving.target.id)
        assertThat(settled.transitioning).isFalse()
        assertThat(settled.roleOf(settled.target)).isEqualTo(SwitchRole.Settled)
    }

    @Test
    fun `a layer id is never reused, so each switch restarts its animation`() {
        val a = SwitchPlan.of("a", key = false)
        val b = a.retarget("b", key = true).settled()
        val c = b.retarget("c", key = false).settled()

        assertThat(setOf(a.target.id, b.target.id, c.target.id)).hasSize(3)
    }

    @Test
    fun `switching again mid-way keeps at most two layers composed`() {
        val plan = SwitchPlan.of("a", key = false).retarget("b", key = true).retarget("c", key = false)

        assertThat(plan.layers.map { it.value }).containsExactly("b", "c").inOrder()
        assertThat(plan.roleOf(plan.layers[0])).isEqualTo(SwitchRole.Outgoing)
        assertThat(plan.roleOf(plan.layers[1])).isEqualTo(SwitchRole.Incoming)
    }

    // --- the frame: how a layer is drawn ---

    @Test
    fun `a settled layer is drawn untouched whatever the progress`() {
        for (progress in steps) assertThat(switchFrame(SwitchRole.Settled, progress)).isEqualTo(SwitchFrame(alpha = 1f, scale = 1f, shift = 0f))
    }

    @Test
    fun `the swap starts on the outgoing layer alone, untouched`() {
        assertThat(switchFrame(SwitchRole.Outgoing, 0f)).isEqualTo(SwitchFrame(alpha = 1f, scale = 1f, shift = 0f))
        assertThat(switchFrame(SwitchRole.Incoming, 0f).alpha).isEqualTo(0f)
    }

    @Test
    fun `the swap ends on the incoming layer alone, untouched`() {
        assertThat(switchFrame(SwitchRole.Incoming, 1f)).isEqualTo(SwitchFrame(alpha = 1f, scale = 1f, shift = 0f))
        assertThat(switchFrame(SwitchRole.Outgoing, 1f).alpha).isEqualTo(0f)
    }

    @Test
    fun `the two layers are never both on show at once`() {
        for (progress in steps) {
            val out = switchFrame(SwitchRole.Outgoing, progress).alpha
            val incoming = switchFrame(SwitchRole.Incoming, progress).alpha
            assertThat(out + incoming).isAtMost(1f)
        }
    }

    @Test
    fun `the outgoing layer only fades out and the incoming one only fades in`() {
        val out = steps.map { switchFrame(SwitchRole.Outgoing, it).alpha }
        val incoming = steps.map { switchFrame(SwitchRole.Incoming, it).alpha }

        assertThat(out.zipWithNext().all { (a, b) -> b <= a }).isTrue()
        assertThat(incoming.zipWithNext().all { (a, b) -> b >= a }).isTrue()
    }

    @Test
    fun `the layers change size and place as they cross`() {
        // The outgoing one shrinks and rises a little; the incoming one settles from a little larger and lower.
        val out = switchFrame(SwitchRole.Outgoing, 1f)
        val incoming = switchFrame(SwitchRole.Incoming, 0f)

        assertThat(out.scale).isLessThan(1f)
        assertThat(out.shift).isLessThan(0f)
        assertThat(incoming.scale).isGreaterThan(1f)
        assertThat(incoming.shift).isGreaterThan(0f)
    }

    @Test
    fun `an out of range progress is clamped`() {
        assertThat(switchFrame(SwitchRole.Incoming, -1f)).isEqualTo(switchFrame(SwitchRole.Incoming, 0f))
        assertThat(switchFrame(SwitchRole.Outgoing, 2f)).isEqualTo(switchFrame(SwitchRole.Outgoing, 1f))
    }

    @Test
    fun `the swap lasts about 300 ms`() {
        assertThat(DJ_SWITCH_MILLIS).isEqualTo(300)
    }

    // --- the calm swap and its warm-up (large windows, T13) ---

    @Test
    fun `a calm swap fades like the standard one but never scales or moves`() {
        for (role in listOf(SwitchRole.Outgoing, SwitchRole.Incoming)) {
            for (progress in steps) {
                val calm = switchFrame(role, progress, calm = true)
                assertThat(calm.alpha).isEqualTo(switchFrame(role, progress).alpha)
                assertThat(calm.scale).isEqualTo(1f)
                assertThat(calm.shift).isEqualTo(0f)
            }
        }
    }

    @Test
    fun `the standard swap is unchanged unless asked to be calm`() {
        assertThat(switchFrame(SwitchRole.Incoming, 0f)).isEqualTo(switchFrame(SwitchRole.Incoming, 0f, calm = false))
        assertThat(switchFrame(SwitchRole.Outgoing, 0.5f).scale).isLessThan(1f)
    }

    @Test
    fun `a calm incoming layer is never fully clear, so it stays drawn from the first frame and is not rendered again when it shows`() {
        for (progress in steps) assertThat(swapAlpha(SwitchRole.Incoming, progress, calm = true)).isAtLeast(CALM_FLOOR_ALPHA)
        assertThat(CALM_FLOOR_ALPHA).isGreaterThan(0f)
        assertThat(CALM_FLOOR_ALPHA).isAtMost(0.05f)
    }

    @Test
    fun `a calm swap fades the incoming layer in and the outgoing one out as the standard one does, above the floor`() {
        for (progress in steps) {
            val standard = switchFrame(SwitchRole.Incoming, progress).alpha
            assertThat(swapAlpha(SwitchRole.Incoming, progress, calm = true)).isEqualTo(maxOf(standard, CALM_FLOOR_ALPHA))
            assertThat(swapAlpha(SwitchRole.Outgoing, progress, calm = true)).isEqualTo(switchFrame(SwitchRole.Outgoing, progress).alpha)
            assertThat(swapAlpha(SwitchRole.Settled, progress, calm = true)).isEqualTo(1f)
        }
    }

    @Test
    fun `a standard swap draws the frame's alpha, down to nothing`() {
        for (role in SwitchRole.entries) {
            for (progress in steps) assertThat(swapAlpha(role, progress, calm = false)).isEqualTo(switchFrame(role, progress).alpha)
        }
        assertThat(swapAlpha(SwitchRole.Incoming, 0f, calm = false)).isEqualTo(0f)
    }

    @Test
    fun `the warm-up is a few frames`() {
        assertThat(DJ_SWITCH_WARM_FRAMES).isIn(1..5)
    }
}
