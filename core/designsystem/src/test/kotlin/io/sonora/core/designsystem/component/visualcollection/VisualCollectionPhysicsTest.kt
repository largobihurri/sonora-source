package io.sonora.core.designsystem.component.visualcollection

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import org.junit.Test

class VisualCollectionPhysicsTest {

    @Test
    fun `a fling settles on the cover nearest to where it would coast`() {
        assertThat(VisualCollectionPhysics.snapIndex(projected = 12.4f, count = 100)).isEqualTo(12)
        assertThat(VisualCollectionPhysics.snapIndex(projected = 12.6f, count = 100)).isEqualTo(13)
        assertThat(VisualCollectionPhysics.snapIndex(projected = 0.2f, count = 100)).isEqualTo(0)
    }

    @Test
    fun `a fling past either end settles on the end cover`() {
        assertThat(VisualCollectionPhysics.snapIndex(projected = -7f, count = 100)).isEqualTo(0)
        assertThat(VisualCollectionPhysics.snapIndex(projected = 250f, count = 100)).isEqualTo(99)
        assertThat(VisualCollectionPhysics.snapIndex(projected = 3f, count = 1)).isEqualTo(0)
    }

    @Test
    fun `a drag moves the strip with the finger, continuously, within the list`() {
        // Dragging right brings earlier covers to the centre.
        assertThat(VisualCollectionPhysics.dragPosition(start = 10f, dragPx = 150f, pxPerItem = 100f, count = 100)).isEqualTo(8.5f)
        assertThat(VisualCollectionPhysics.dragPosition(start = 10f, dragPx = -25f, pxPerItem = 100f, count = 100)).isEqualTo(10.25f)
        assertThat(VisualCollectionPhysics.dragPosition(start = 1f, dragPx = 500f, pxPerItem = 100f, count = 100)).isEqualTo(0f)
        assertThat(VisualCollectionPhysics.dragPosition(start = 98f, dragPx = -500f, pxPerItem = 100f, count = 100)).isEqualTo(99f)
    }

    @Test
    fun `finger velocity becomes item velocity in the strip's direction`() {
        assertThat(VisualCollectionPhysics.itemVelocity(pxPerSecond = 2_000f, pxPerItem = 100f)).isEqualTo(-20f)
        assertThat(VisualCollectionPhysics.itemVelocity(pxPerSecond = -500f, pxPerItem = 100f)).isEqualTo(5f)
    }

    @Test
    fun `a far fling coasts through the covers before settling, a short one springs straight there`() {
        assertThat(VisualCollectionPhysics.coastsFirst(position = 10f, target = 30, velocity = 40f)).isTrue()
        assertThat(VisualCollectionPhysics.coastsFirst(position = 10f, target = 10, velocity = 0.5f)).isFalse()
        // Snapping back against the fling's direction never coasts.
        assertThat(VisualCollectionPhysics.coastsFirst(position = 10.4f, target = 10, velocity = 3f)).isFalse()
    }

    @Test
    fun `a fling's glide ends on its cover with no speed left, in one move`() {
        // Starts at the fling's speed: the curve's slope at 0 times distance over duration is the velocity.
        val ms = VisualCollectionPhysics.glideDurationMs(distance = 20f, velocity = 40f)
        assertThat(ms).isEqualTo(1_500)
        assertThat(VisualCollectionPhysics.glide(0f)).isEqualTo(0f)
        assertThat(VisualCollectionPhysics.glide(1f)).isEqualTo(1f)
        val step = 0.001f
        assertThat(VisualCollectionPhysics.glide(step) / step).isWithin(0.01f).of(VisualCollectionPhysics.GLIDE_START_SLOPE)
        // No speed left at the end: the last step barely moves.
        assertThat((1f - VisualCollectionPhysics.glide(1f - step)) / step).isLessThan(0.01f)
    }

    @Test
    fun `the glide only ever moves forward`() {
        var last = 0f
        for (i in 1..100) {
            val now = VisualCollectionPhysics.glide(i / 100f)
            assertThat(now).isAtLeast(last)
            last = now
        }
    }

    @Test
    fun `glides stay between a quick settle and a long coast`() {
        assertThat(VisualCollectionPhysics.glideDurationMs(distance = 1f, velocity = 60f)).isEqualTo(VisualCollectionPhysics.MIN_GLIDE_MS)
        assertThat(VisualCollectionPhysics.glideDurationMs(distance = 300f, velocity = 10f)).isEqualTo(VisualCollectionPhysics.MAX_GLIDE_MS)
        assertThat(VisualCollectionPhysics.glideDurationMs(distance = -6f, velocity = -12f)).isEqualTo(1_500)
    }

    @Test
    fun `an unclamped glide keeps its curve`() {
        val ms = VisualCollectionPhysics.glideDurationMs(distance = 20f, velocity = 40f)
        assertThat(VisualCollectionPhysics.glideStartSlope(distance = 20f, velocity = 40f, durationMs = ms))
            .isWithin(1e-4f).of(VisualCollectionPhysics.GLIDE_START_SLOPE)
        for (i in 0..20) {
            val f = i / 20f
            assertThat(VisualCollectionPhysics.glide(f, VisualCollectionPhysics.GLIDE_START_SLOPE)).isWithin(1e-5f).of(VisualCollectionPhysics.glide(f))
        }
    }

    @Test
    fun `a clamped glide still leaves at the fling's speed and ends on its cover with no speed left`() {
        // Very fast short flings hit the shortest glide, very slow long ones the longest.
        val flings = listOf(1f to 60f, -1f to -60f, 2f to 400f, 300f to 10f, 40f to 20f)
        for ((distance, velocity) in flings) {
            val ms = VisualCollectionPhysics.glideDurationMs(distance, velocity)
            assertThat(ms).isAnyOf(VisualCollectionPhysics.MIN_GLIDE_MS, VisualCollectionPhysics.MAX_GLIDE_MS)
            val slope = VisualCollectionPhysics.glideStartSlope(distance, velocity, ms)
            val step = 0.0001f
            val startSpeed = abs(distance) * VisualCollectionPhysics.glide(step, slope) / step / (ms / 1_000f)
            assertWithMessage("fling $distance at $velocity").that(startSpeed).isWithin(abs(velocity) * 0.02f).of(abs(velocity))
            assertThat(VisualCollectionPhysics.glide(0f, slope)).isEqualTo(0f)
            assertThat(VisualCollectionPhysics.glide(1f, slope)).isWithin(1e-6f).of(1f)
            assertThat((1f - VisualCollectionPhysics.glide(1f - step, slope)) / step).isLessThan(0.01f)
            var last = 0f
            for (i in 1..200) {
                val now = VisualCollectionPhysics.glide(i / 200f, slope)
                assertWithMessage("fling $distance at $velocity, step $i").that(now).isAtLeast(last)
                assertThat(now).isAtMost(1f)
                last = now
            }
        }
    }

    @Test
    fun `a fling below the cap keeps its speed, one at or above it leaves at the cap`() {
        val cap = VisualCollectionPhysics.MAX_SPEED
        assertThat(VisualCollectionPhysics.cappedVelocity(20f)).isEqualTo(20f)
        assertThat(VisualCollectionPhysics.cappedVelocity(-20f)).isEqualTo(-20f)
        assertThat(VisualCollectionPhysics.cappedVelocity(cap)).isEqualTo(cap)
        assertThat(VisualCollectionPhysics.cappedVelocity(-cap)).isEqualTo(-cap)
        assertThat(VisualCollectionPhysics.cappedVelocity(cap * 4f)).isEqualTo(cap)
        assertThat(VisualCollectionPhysics.cappedVelocity(-cap * 4f)).isEqualTo(-cap)
    }

    @Test
    fun `a fling below the cap glides as it always did`() {
        assertThat(VisualCollectionPhysics.flingGlideMs(distance = 20f, velocity = 40f))
            .isEqualTo(VisualCollectionPhysics.glideDurationMs(distance = 20f, velocity = 40f))
        assertThat(VisualCollectionPhysics.flingGlideMs(distance = -6f, velocity = -12f)).isEqualTo(1_500)
    }

    @Test
    fun `a fling above the cap still travels as far, for longer`() {
        // The distance comes from the uncapped fling; only its speed is capped.
        val distance = 150f
        val velocity = 280f
        val uncapped = VisualCollectionPhysics.glideDurationMs(distance, velocity)
        val ms = VisualCollectionPhysics.flingGlideMs(distance, velocity)
        assertThat(ms).isGreaterThan(uncapped)
        val slope = VisualCollectionPhysics.glideStartSlope(distance, VisualCollectionPhysics.cappedVelocity(velocity), ms)
        assertThat(VisualCollectionPhysics.glide(1f, slope)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `no frame of a glide advances more than the cap allows, and it leaves at the capped speed`() {
        val frameMs = 1_000f / 120f
        val maxStep = VisualCollectionPhysics.MAX_SPEED * frameMs / 1_000f
        // About one cover per frame at most, even at 120 Hz.
        assertThat(maxStep).isAtMost(1f)
        val cap = VisualCollectionPhysics.MAX_SPEED
        val flings = listOf(
            20f to 40f, 32f to cap, 150f to 280f, -150f to -280f, 600f to 900f, 2f to 400f, 300f to 10f, 40f to 20f,
        )
        for ((distance, velocity) in flings) {
            val leaving = VisualCollectionPhysics.cappedVelocity(velocity)
            val ms = VisualCollectionPhysics.flingGlideMs(distance, leaving)
            val slope = VisualCollectionPhysics.glideStartSlope(distance, leaving, ms)
            val step = 0.0001f
            val startSpeed = abs(distance) * VisualCollectionPhysics.glide(step, slope) / step / (ms / 1_000f)
            assertWithMessage("fling $distance at $velocity leaves").that(startSpeed).isWithin(abs(leaving) * 0.02f).of(abs(leaving))
            val frames = (ms / frameMs).toInt() + 1
            var last = 0f
            for (i in 1..frames) {
                val now = abs(distance) * VisualCollectionPhysics.glide(i * frameMs / ms, slope)
                assertWithMessage("fling $distance at $velocity, frame $i of $frames").that(now - last).isAtMost(maxStep * 1.01f)
                last = now
            }
            assertThat(last).isWithin(1e-3f * abs(distance)).of(abs(distance))
        }
    }

    @Test
    fun `a drag follows a slow finger exactly and chases a fast one at the cap`() {
        val frameMs = 1_000f / 120f
        val maxStep = VisualCollectionPhysics.MAX_SPEED * frameMs / 1_000f
        assertThat(VisualCollectionPhysics.chase(current = 10f, target = 10.3f, elapsedMs = frameMs)).isEqualTo(10.3f)
        assertThat(VisualCollectionPhysics.chase(current = 10f, target = 9.8f, elapsedMs = frameMs)).isEqualTo(9.8f)
        for (target in listOf(30f, -10f)) {
            var at = 10f
            repeat(10) {
                val next = VisualCollectionPhysics.chase(at, target, frameMs)
                assertThat(abs(next - at)).isWithin(1e-4f).of(maxStep)
                at = next
            }
            // Once the finger rests, the strip catches up with it and stops there.
            repeat(200) { at = VisualCollectionPhysics.chase(at, target, frameMs) }
            assertThat(at).isEqualTo(target)
        }
    }
}
