package io.sonora.core.designsystem.component.coverflow

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import org.junit.Test

class CoverFlowPhysicsTest {

    @Test
    fun `a fling settles on the cover nearest to where it would coast`() {
        assertThat(CoverFlowPhysics.snapIndex(projected = 12.4f, count = 100)).isEqualTo(12)
        assertThat(CoverFlowPhysics.snapIndex(projected = 12.6f, count = 100)).isEqualTo(13)
        assertThat(CoverFlowPhysics.snapIndex(projected = 0.2f, count = 100)).isEqualTo(0)
    }

    @Test
    fun `a fling past either end settles on the end cover`() {
        assertThat(CoverFlowPhysics.snapIndex(projected = -7f, count = 100)).isEqualTo(0)
        assertThat(CoverFlowPhysics.snapIndex(projected = 250f, count = 100)).isEqualTo(99)
        assertThat(CoverFlowPhysics.snapIndex(projected = 3f, count = 1)).isEqualTo(0)
    }

    @Test
    fun `a drag moves the strip with the finger, continuously, within the list`() {
        // Dragging right brings earlier covers to the centre.
        assertThat(CoverFlowPhysics.dragPosition(start = 10f, dragPx = 150f, pxPerItem = 100f, count = 100)).isEqualTo(8.5f)
        assertThat(CoverFlowPhysics.dragPosition(start = 10f, dragPx = -25f, pxPerItem = 100f, count = 100)).isEqualTo(10.25f)
        assertThat(CoverFlowPhysics.dragPosition(start = 1f, dragPx = 500f, pxPerItem = 100f, count = 100)).isEqualTo(0f)
        assertThat(CoverFlowPhysics.dragPosition(start = 98f, dragPx = -500f, pxPerItem = 100f, count = 100)).isEqualTo(99f)
    }

    @Test
    fun `finger velocity becomes item velocity in the strip's direction`() {
        assertThat(CoverFlowPhysics.itemVelocity(pxPerSecond = 2_000f, pxPerItem = 100f)).isEqualTo(-20f)
        assertThat(CoverFlowPhysics.itemVelocity(pxPerSecond = -500f, pxPerItem = 100f)).isEqualTo(5f)
    }

    @Test
    fun `a far fling coasts through the covers before settling, a short one springs straight there`() {
        assertThat(CoverFlowPhysics.coastsFirst(position = 10f, target = 30, velocity = 40f)).isTrue()
        assertThat(CoverFlowPhysics.coastsFirst(position = 10f, target = 10, velocity = 0.5f)).isFalse()
        // Snapping back against the fling's direction never coasts.
        assertThat(CoverFlowPhysics.coastsFirst(position = 10.4f, target = 10, velocity = 3f)).isFalse()
    }

    @Test
    fun `a fling's glide ends on its cover with no speed left, in one move`() {
        // Starts at the fling's speed: the curve's slope at 0 times distance over duration is the velocity.
        val ms = CoverFlowPhysics.glideDurationMs(distance = 20f, velocity = 40f)
        assertThat(ms).isEqualTo(1_500)
        assertThat(CoverFlowPhysics.glide(0f)).isEqualTo(0f)
        assertThat(CoverFlowPhysics.glide(1f)).isEqualTo(1f)
        val step = 0.001f
        assertThat(CoverFlowPhysics.glide(step) / step).isWithin(0.01f).of(CoverFlowPhysics.GLIDE_START_SLOPE)
        // No speed left at the end: the last step barely moves.
        assertThat((1f - CoverFlowPhysics.glide(1f - step)) / step).isLessThan(0.01f)
    }

    @Test
    fun `the glide only ever moves forward`() {
        var last = 0f
        for (i in 1..100) {
            val now = CoverFlowPhysics.glide(i / 100f)
            assertThat(now).isAtLeast(last)
            last = now
        }
    }

    @Test
    fun `glides stay between a quick settle and a long coast`() {
        assertThat(CoverFlowPhysics.glideDurationMs(distance = 1f, velocity = 60f)).isEqualTo(CoverFlowPhysics.MIN_GLIDE_MS)
        assertThat(CoverFlowPhysics.glideDurationMs(distance = 300f, velocity = 10f)).isEqualTo(CoverFlowPhysics.MAX_GLIDE_MS)
        assertThat(CoverFlowPhysics.glideDurationMs(distance = -6f, velocity = -12f)).isEqualTo(1_500)
    }

    @Test
    fun `an unclamped glide keeps its curve`() {
        val ms = CoverFlowPhysics.glideDurationMs(distance = 20f, velocity = 40f)
        assertThat(CoverFlowPhysics.glideStartSlope(distance = 20f, velocity = 40f, durationMs = ms))
            .isWithin(1e-4f).of(CoverFlowPhysics.GLIDE_START_SLOPE)
        for (i in 0..20) {
            val f = i / 20f
            assertThat(CoverFlowPhysics.glide(f, CoverFlowPhysics.GLIDE_START_SLOPE)).isWithin(1e-5f).of(CoverFlowPhysics.glide(f))
        }
    }

    @Test
    fun `a clamped glide still leaves at the fling's speed and ends on its cover with no speed left`() {
        // Very fast short flings hit the shortest glide, very slow long ones the longest.
        val flings = listOf(1f to 60f, -1f to -60f, 2f to 400f, 300f to 10f, 40f to 20f)
        for ((distance, velocity) in flings) {
            val ms = CoverFlowPhysics.glideDurationMs(distance, velocity)
            assertThat(ms).isAnyOf(CoverFlowPhysics.MIN_GLIDE_MS, CoverFlowPhysics.MAX_GLIDE_MS)
            val slope = CoverFlowPhysics.glideStartSlope(distance, velocity, ms)
            val step = 0.0001f
            val startSpeed = abs(distance) * CoverFlowPhysics.glide(step, slope) / step / (ms / 1_000f)
            assertWithMessage("fling $distance at $velocity").that(startSpeed).isWithin(abs(velocity) * 0.02f).of(abs(velocity))
            assertThat(CoverFlowPhysics.glide(0f, slope)).isEqualTo(0f)
            assertThat(CoverFlowPhysics.glide(1f, slope)).isWithin(1e-6f).of(1f)
            assertThat((1f - CoverFlowPhysics.glide(1f - step, slope)) / step).isLessThan(0.01f)
            var last = 0f
            for (i in 1..200) {
                val now = CoverFlowPhysics.glide(i / 200f, slope)
                assertWithMessage("fling $distance at $velocity, step $i").that(now).isAtLeast(last)
                assertThat(now).isAtMost(1f)
                last = now
            }
        }
    }

    @Test
    fun `a fling below the cap keeps its speed, one at or above it leaves at the cap`() {
        val cap = CoverFlowPhysics.MAX_SPEED
        assertThat(CoverFlowPhysics.cappedVelocity(20f)).isEqualTo(20f)
        assertThat(CoverFlowPhysics.cappedVelocity(-20f)).isEqualTo(-20f)
        assertThat(CoverFlowPhysics.cappedVelocity(cap)).isEqualTo(cap)
        assertThat(CoverFlowPhysics.cappedVelocity(-cap)).isEqualTo(-cap)
        assertThat(CoverFlowPhysics.cappedVelocity(cap * 4f)).isEqualTo(cap)
        assertThat(CoverFlowPhysics.cappedVelocity(-cap * 4f)).isEqualTo(-cap)
    }

    @Test
    fun `a fling below the cap glides as it always did`() {
        assertThat(CoverFlowPhysics.flingGlideMs(distance = 20f, velocity = 40f))
            .isEqualTo(CoverFlowPhysics.glideDurationMs(distance = 20f, velocity = 40f))
        assertThat(CoverFlowPhysics.flingGlideMs(distance = -6f, velocity = -12f)).isEqualTo(1_500)
    }

    @Test
    fun `a fling above the cap still travels as far, for longer`() {
        // The distance comes from the uncapped fling; only its speed is capped.
        val distance = 150f
        val velocity = 280f
        val uncapped = CoverFlowPhysics.glideDurationMs(distance, velocity)
        val ms = CoverFlowPhysics.flingGlideMs(distance, velocity)
        assertThat(ms).isGreaterThan(uncapped)
        val slope = CoverFlowPhysics.glideStartSlope(distance, CoverFlowPhysics.cappedVelocity(velocity), ms)
        assertThat(CoverFlowPhysics.glide(1f, slope)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `no frame of a glide advances more than the cap allows, and it leaves at the capped speed`() {
        val frameMs = 1_000f / 120f
        val maxStep = CoverFlowPhysics.MAX_SPEED * frameMs / 1_000f
        // About one cover per frame at most, even at 120 Hz.
        assertThat(maxStep).isAtMost(1f)
        val cap = CoverFlowPhysics.MAX_SPEED
        val flings = listOf(
            20f to 40f, 32f to cap, 150f to 280f, -150f to -280f, 600f to 900f, 2f to 400f, 300f to 10f, 40f to 20f,
        )
        for ((distance, velocity) in flings) {
            val leaving = CoverFlowPhysics.cappedVelocity(velocity)
            val ms = CoverFlowPhysics.flingGlideMs(distance, leaving)
            val slope = CoverFlowPhysics.glideStartSlope(distance, leaving, ms)
            val step = 0.0001f
            val startSpeed = abs(distance) * CoverFlowPhysics.glide(step, slope) / step / (ms / 1_000f)
            assertWithMessage("fling $distance at $velocity leaves").that(startSpeed).isWithin(abs(leaving) * 0.02f).of(abs(leaving))
            val frames = (ms / frameMs).toInt() + 1
            var last = 0f
            for (i in 1..frames) {
                val now = abs(distance) * CoverFlowPhysics.glide(i * frameMs / ms, slope)
                assertWithMessage("fling $distance at $velocity, frame $i of $frames").that(now - last).isAtMost(maxStep * 1.01f)
                last = now
            }
            assertThat(last).isWithin(1e-3f * abs(distance)).of(abs(distance))
        }
    }

    @Test
    fun `a drag follows a slow finger exactly and chases a fast one at the cap`() {
        val frameMs = 1_000f / 120f
        val maxStep = CoverFlowPhysics.MAX_SPEED * frameMs / 1_000f
        assertThat(CoverFlowPhysics.chase(current = 10f, target = 10.3f, elapsedMs = frameMs)).isEqualTo(10.3f)
        assertThat(CoverFlowPhysics.chase(current = 10f, target = 9.8f, elapsedMs = frameMs)).isEqualTo(9.8f)
        for (target in listOf(30f, -10f)) {
            var at = 10f
            repeat(10) {
                val next = CoverFlowPhysics.chase(at, target, frameMs)
                assertThat(abs(next - at)).isWithin(1e-4f).of(maxStep)
                at = next
            }
            // Once the finger rests, the strip catches up with it and stops there.
            repeat(200) { at = CoverFlowPhysics.chase(at, target, frameMs) }
            assertThat(at).isEqualTo(target)
        }
    }
}
