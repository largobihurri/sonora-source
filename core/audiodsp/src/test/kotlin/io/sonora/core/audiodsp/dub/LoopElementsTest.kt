package io.sonora.core.audiodsp.dub

import com.google.common.truth.Truth.assertThat
import io.sonora.core.audiodsp.dub.DubSignals.db
import io.sonora.core.audiodsp.dub.DubSignals.rms
import io.sonora.core.audiodsp.dub.DubSignals.sine
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Test

/** Smoother, DcBlocker and SoftSaturator on their own. */
class LoopElementsTest {

    @Test
    fun `the smoother reaches 63 percent after one time constant and never overshoots`() {
        val s = Smoother()
        s.configure(0.01, 48_000.0) // 480 frames
        s.setTarget(1.0)
        var previous = 0.0
        var at480 = 0.0
        for (i in 1..4_800) {
            val v = s.next()
            assertThat(v).isAtLeast(previous)
            assertThat(v).isAtMost(1.0)
            if (i == 480) at480 = v
            previous = v
        }
        assertThat(at480).isWithin(1e-3).of(1.0 - exp(-1.0))
    }

    @Test
    fun `a settled smoother is bit-exact on its target`() {
        val s = Smoother()
        s.configure(0.05, 48_000.0)
        s.setTarget(36_000.0)
        repeat(48_000) { s.next() }
        assertThat(s.value).isEqualTo(36_000.0)
        assertThat(s.isSettled).isTrue()
    }

    @Test
    fun `a non-positive or NaN time constant jumps at once`() {
        for (tau in doubleArrayOf(0.0, -1.0, Double.NaN)) {
            val s = Smoother()
            s.configure(tau, 48_000.0)
            s.setTarget(0.7)
            assertThat(s.next()).isEqualTo(0.7)
        }
    }

    @Test
    fun `the DC blocker removes DC and passes the audio band`() {
        val rate = 48_000.0
        val dc = DcBlocker().apply { prepare(5f, rate.toFloat()) }
        var last = 1f
        repeat(48_000) { last = dc.process(0.5f) }
        assertThat(abs(last)).isLessThan(1e-3f)

        val tone = DcBlocker().apply { prepare(5f, rate.toFloat()) }
        val out = FloatArray(96_000) { tone.process(sine(1_000.0, 0.5, it, rate)) }
        val gainDb = db(rms(out, 48_000, 96_000) / (0.5 / kotlin.math.sqrt(2.0)))
        assertThat(abs(gainDb)).isLessThan(0.01)
    }

    @Test
    fun `the DC blocker flushes a decaying tail to exact zero`() {
        val dc = DcBlocker().apply { prepare(5f, 48_000f) }
        dc.process(1f)
        repeat(10 * 48_000) { dc.process(0f) }
        assertThat(dc.process(0f)).isEqualTo(0f)
    }

    @Test
    fun `the saturator follows its rational curve and maps NaN to silence`() {
        for (x in floatArrayOf(-2.5f, -0.7f, 0.3f, 1.4f)) {
            assertThat(SoftSaturator.process(x)).isWithin(1e-6f).of(x * (27f + x * x) / (27f + 9f * x * x))
        }
        assertThat(SoftSaturator.process(Float.NaN)).isEqualTo(0f)
        assertThat(SoftSaturator.process(Float.NEGATIVE_INFINITY)).isEqualTo(-1f)
    }

    @Test
    fun `the knee limiter is unity up to its knee and bends smoothly towards its bound`() {
        for (x in floatArrayOf(0f, 0.1f, -0.35f, 0.5f, -0.69f, KneeLimiter.KNEE, -KneeLimiter.KNEE)) {
            assertThat(KneeLimiter.process(x)).isEqualTo(x)
        }
        // Continuous value and slope at the knee: the slope just above it is still 1.
        val h = 1e-3f
        val k = KneeLimiter.KNEE
        assertThat((KneeLimiter.process(k + h) - KneeLimiter.process(k)) / h).isWithin(0.01f).of(1f)
        assertThat(-KneeLimiter.process(-k - h)).isEqualTo(KneeLimiter.process(k + h))
        // Monotonic and below the bound everywhere, extreme and non-finite inputs included.
        var previous = KneeLimiter.process(k)
        var x = k
        while (x < 100f) {
            x += 0.01f
            val y = KneeLimiter.process(x)
            assertThat(y).isAtLeast(previous)
            assertThat(y).isAtMost(KneeLimiter.BOUND)
            previous = y
        }
        assertThat(KneeLimiter.process(1e30f)).isAtMost(KneeLimiter.BOUND)
        assertThat(KneeLimiter.process(Float.POSITIVE_INFINITY)).isEqualTo(KneeLimiter.BOUND)
        assertThat(KneeLimiter.process(Float.NEGATIVE_INFINITY)).isEqualTo(-KneeLimiter.BOUND)
        assertThat(KneeLimiter.process(Float.NaN)).isEqualTo(0f)
    }
}
