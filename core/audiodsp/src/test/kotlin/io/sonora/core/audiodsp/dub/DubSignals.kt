package io.sonora.core.audiodsp.dub

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Test signals and measurements shared by the dub stage tests. */
object DubSignals {

    const val TWO_PI = 2.0 * PI

    fun sine(hz: Double, amplitude: Double, frame: Int, rateHz: Double): Float =
        (amplitude * sin(TWO_PI * hz * frame / rateHz)).toFloat()

    /** Hann-windowed sine burst sample (no broadband onset click); zero from [burstFrames] on. */
    fun burst(hz: Double, amplitude: Double, frame: Int, burstFrames: Int, rateHz: Double): Float {
        if (frame >= burstFrames) return 0f
        val window = 0.5 - 0.5 * cos(TWO_PI * frame / burstFrames)
        return (amplitude * window * sin(TWO_PI * hz * frame / rateHz)).toFloat()
    }

    fun sum(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i]
        return s
    }

    fun energy(x: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return e
    }

    fun rms(x: FloatArray, from: Int, to: Int): Double = sqrt(energy(x, from, to) / (to - from))

    fun db(ratio: Double): Double = 20.0 * log10(ratio)

    fun argMaxAbs(x: FloatArray, from: Int, to: Int): Int {
        var best = from
        for (i in from until to) if (abs(x[i]) > abs(x[best])) best = i
        return best
    }

    /**
     * First sample reaching a quarter of the window's peak. A plain "first non-zero" would not work: the DC blocker
     * leaves a tiny (about -60 dB) slow tail between echoes.
     */
    fun onset(x: FloatArray, from: Int, to: Int): Int {
        val threshold = 0.25f * abs(x[argMaxAbs(x, from, to)])
        for (i in from until to) if (abs(x[i]) >= threshold) return i
        return to
    }

    fun maxStep(x: FloatArray, from: Int, to: Int): Float {
        var step = 0f
        for (i in maxOf(from, 1) until to) step = maxOf(step, abs(x[i] - x[i - 1]))
        return step
    }

    fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var p = 0f
        for (i in from until to) p = maxOf(p, abs(x[i]))
        return p
    }

    /** Deterministic white noise in [-amplitude, amplitude] (xorshift), for repeatable tests. */
    class Noise(seed: Long, private val amplitude: Float) {
        private var state = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            val unit = (state ushr 40).toFloat() / (1L shl 24).toFloat()
            return amplitude * (2f * unit - 1f)
        }
    }
}
