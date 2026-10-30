package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import org.junit.Test

class XyMappingTest {

    @Test
    fun `the centre of X is the flat detent`() {
        val value = XyMapping.valueAt(XyPosition(0.5f, 0.2f))
        assertThat(value.mode).isEqualTo(XyFilterMode.FLAT)
    }

    @Test
    fun `the left edge is a 200 Hz low-pass and the right edge a 6 kHz high-pass`() {
        val left = XyMapping.valueAt(XyPosition(0f, 0f))
        assertThat(left.mode).isEqualTo(XyFilterMode.LOW_PASS)
        assertThat(left.cutoffHz).isWithin(0.01f).of(200f)
        val right = XyMapping.valueAt(XyPosition(1f, 0f))
        assertThat(right.mode).isEqualTo(XyFilterMode.HIGH_PASS)
        assertThat(right.cutoffHz).isWithin(0.1f).of(6_000f)
    }

    @Test
    fun `each side is a log map from fully open at the detent to its edge`() {
        assertThat(XyMapping.valueAt(XyPosition(0.5f - 1e-4f, 0f)).cutoffHz).isWithin(50f).of(20_000f)
        assertThat(XyMapping.valueAt(XyPosition(0.25f, 0f)).cutoffHz).isWithin(1f).of(sqrt(20_000f * 200f))
        assertThat(XyMapping.valueAt(XyPosition(0.5f + 1e-4f, 0f)).cutoffHz).isWithin(0.1f).of(20f)
        assertThat(XyMapping.valueAt(XyPosition(0.75f, 0f)).cutoffHz).isWithin(0.1f).of(sqrt(20f * 6_000f))
    }

    @Test
    fun `Y runs the resonance from Q 0_7 at the bottom to Q 8 at the top on a log map`() {
        assertThat(XyMapping.qAt(0f)).isWithin(1e-4f).of(0.7f)
        assertThat(XyMapping.qAt(1f)).isWithin(1e-4f).of(8f)
        assertThat(XyMapping.qAt(0.5f)).isWithin(1e-3f).of(sqrt(0.7f * 8f))
        assertThat(XyMapping.yOfQ(XyMapping.qAt(0.3f))).isWithin(1e-5f).of(0.3f)
    }

    @Test
    fun `positions map back from a mode and a cutoff`() {
        for (x in listOf(0f, 0.1f, 0.3f, 0.45f, 0.55f, 0.8f, 1f)) {
            val value = XyMapping.valueAt(XyPosition(x, 0f))
            assertThat(XyMapping.xOf(value.mode, value.cutoffHz)).isWithin(1e-4f).of(x)
        }
        assertThat(XyMapping.xOf(XyFilterMode.FLAT, 0f)).isEqualTo(0.5f)
    }

    @Test
    fun `a finger in the detent zone reads as flat and the edges stay reachable`() {
        val inZone = XyMapping.touch(XyMapping.Centre, rawX = 0.5f + XyMapping.DETENT_ZONE / 2f, rawY = 0.5f)
        assertThat(inZone.position.x).isEqualTo(0.5f)
        assertThat(XyMapping.touch(XyMapping.Centre, rawX = 0f, rawY = 0f).position.x).isEqualTo(0f)
        assertThat(XyMapping.touch(XyMapping.Centre, rawX = 1f, rawY = 1f).position).isEqualTo(XyPosition(1f, 1f))
    }

    @Test
    fun `crossing into the detent ticks once and moving inside it does not tick again`() {
        var position = XyPosition(0.2f, 0.5f)
        val ticks = mutableListOf<Boolean>()
        for (rawX in listOf(0.3f, 0.4f, 0.49f, 0.5f, 0.51f, 0.5f)) {
            val touch = XyMapping.touch(position, rawX, 0.5f)
            ticks += touch.hitsDetent
            position = touch.position
        }
        assertThat(ticks).containsExactly(false, false, true, false, false, false).inOrder()
    }

    @Test
    fun `jumping across the detent ticks, leaving it does not`() {
        val across = XyMapping.touch(XyPosition(0.1f, 0.5f), 0.9f, 0.5f)
        assertThat(across.hitsDetent).isTrue()
        val leaving = XyMapping.touch(XyMapping.Centre, 0.9f, 0.5f)
        assertThat(leaving.hitsDetent).isFalse()
    }

    @Test
    fun `a non-finite finger keeps the last position`() {
        val start = XyPosition(0.3f, 0.6f)
        assertThat(XyMapping.touch(start, Float.NaN, Float.POSITIVE_INFINITY).position).isEqualTo(start)
    }

    @Test
    fun `lifting glides to the centre unless LOCK is on`() {
        assertThat(XyMapping.liftTarget(locked = false)).isEqualTo(XyMapping.Centre)
        assertThat(XyMapping.liftTarget(locked = true)).isNull()
        assertThat(XyMapping.Centre).isEqualTo(XyPosition(0.5f, 0.5f))
        assertThat(XyMapping.GLIDE_MS).isEqualTo(150)
    }

    @Test
    fun `the glide eases from the start to the target and lands exactly`() {
        val from = XyPosition(0f, 1f)
        assertThat(XyMapping.glide(from, XyMapping.Centre, 0f)).isEqualTo(from)
        assertThat(XyMapping.glide(from, XyMapping.Centre, 1f)).isEqualTo(XyMapping.Centre)
        val half = XyMapping.glide(from, XyMapping.Centre, 0.5f)
        assertThat(half.x).isGreaterThan(0.25f)
        assertThat(half.x).isLessThan(0.5f)
    }

    @Test
    fun `the edge labels show only on a pad large enough to keep them off the centre`() {
        assertThat(XyMapping.showsEdgeLabels(side = 48, minSide = 120)).isFalse()
        assertThat(XyMapping.showsEdgeLabels(side = 119, minSide = 120)).isFalse()
        assertThat(XyMapping.showsEdgeLabels(side = 120, minSide = 120)).isTrue()
    }
}
