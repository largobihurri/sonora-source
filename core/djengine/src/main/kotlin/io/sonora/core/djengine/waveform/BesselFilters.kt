package io.sonora.core.djengine.waveform

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Digital Bessel (maximally flat group delay) filters, designed from the analogue prototype by the bilinear transform
 * with frequency pre-warping, and run as cascades of biquads ([BiquadCascade]). This is the filter family of the Mixxx
 * waveform analyser (a 4th-order Bessel filter bank, built there on Jim Peters' fidlib); the design below is written
 * from the textbook maths, not from that code.
 *
 * ## Design
 * 1. **Prototype.** The Bessel low-pass of order `n` has the transfer function `H(s) = theta_n(0) / theta_n(s)`, where
 *    `theta_n` is the reverse Bessel polynomial, `theta_k = (2k - 1) theta_(k-1) + s^2 theta_(k-2)` with `theta_0 = 1`,
 *    `theta_1 = s + 1` (Thomson 1949; Krall and Frink 1949). For `n = 4`:
 *    `theta_4 = s^4 + 10 s^3 + 45 s^2 + 105 s + 105`. Its group delay is 1 s at DC, flat to the highest degree.
 *    The roots of `theta_n` (found here by Durand-Kerner iteration) are the poles; for even `n` they form `n / 2`
 *    complex-conjugate pairs and there is no real pole.
 * 2. **Cutoff.** `theta_n(0)/theta_n(s)` is down 3 dB at `w3` rad/s (2.1139 for `n = 4`), found by bisection on
 *    `|theta_n(jw)|^2 = 2 theta_n(0)^2`. Dividing the poles by `w3` puts the -3 dB point of the prototype at 1 rad/s,
 *    so the cutoff of the filter is its -3 dB frequency (the Bessel response is gentle: unlike a Butterworth filter
 *    nothing else, such as a slope or a group delay, defines "the" cutoff).
 * 3. **Frequency transformation.** A conjugate pair `p, p*` with `p = s0 + j w0` gives the section
 *    `P2 / (u^2 - 2 s0 u + P2)`, `P2 = |p|^2`, in the normalised variable `u`. The low-pass uses `u = s / wc` and the
 *    high-pass (low-pass to high-pass, `s -> wc / s`) `u = wc / s`.
 * 4. **Bilinear transform with pre-warping.** `s = 2 fs (z - 1) / (z + 1)` maps the analogue frequency axis onto the
 *    unit circle with a warp, `w_analogue = 2 fs tan(pi f / fs)`. Choosing the analogue cutoff `wc = 2 fs K` with
 *    `K = tan(pi fc / fs)` makes the digital filter exactly -3 dB at `fc`; elsewhere it follows the prototype with the
 *    usual compression near Nyquist (Oppenheim and Schafer, Discrete-Time Signal Processing, ch. 7; Orfanidis,
 *    Introduction to Signal Processing, ch. 8). Substituting `u = (z - 1) / (K (z + 1))` and collecting powers of `z`
 *    gives, with `a0` the `z^2` coefficient of the denominator, the biquad coefficients
 *    - low-pass: `a0 = 1 - 2 s0 K + P2 K^2`, `b0 = b2 = P2 K^2 / a0`, `b1 = 2 b0`, `a1 = 2 (P2 K^2 - 1) / a0`,
 *      `a2 = (1 + 2 s0 K + P2 K^2) / a0` (two zeros at `z = -1`, unit gain at DC);
 *    - high-pass: `a0 = K^2 - 2 s0 K + P2`, `b0 = b2 = P2 / a0`, `b1 = -2 b0`, `a1 = 2 (K^2 - P2) / a0`,
 *      `a2 = (K^2 + 2 s0 K + P2) / a0` (two zeros at `z = 1`, unit gain at Nyquist).
 *    `s0 < 0` for a stable prototype, so `a0 > 0`.
 *
 * The band-pass is a high-pass at the lower edge in series with a low-pass at the upper edge: each edge is -3 dB at
 * its own frequency up to the small interaction between the edges, which the width of the band keeps low (the tests
 * pin the edge gains within 0.25 dB). Fidlib builds its Bessel band-pass by the low-pass to band-pass transformation
 * of the prototype instead; the series form is simpler and has the same 24 dB per octave skirts.
 */
object BesselFilters {

    /** Order of every filter built here: a low-pass or high-pass is `ORDER / 2` biquads. */
    const val ORDER: Int = 4

    private const val ROOT_ITERATIONS = 1_000
    private const val ROOT_TOLERANCE = 1e-15
    private const val BISECTION_STEPS = 200

    /** A prototype pole `re + j im` (the conjugate `re - j im` is implied). */
    internal class Pole(val re: Double, val im: Double)

    /**
     * Coefficients of the reverse Bessel polynomial `theta_order`, constant term first (`order + 1` values; the last is
     * 1).
     */
    internal fun besselPolynomial(order: Int): DoubleArray {
        require(order >= 1) { "order must be at least 1: $order" }
        var previous = doubleArrayOf(1.0) // theta_0
        var current = doubleArrayOf(1.0, 1.0) // theta_1
        for (k in 2..order) {
            val next = DoubleArray(k + 1)
            for (i in current.indices) next[i] += (2 * k - 1) * current[i]
            for (i in previous.indices) next[i + 2] += previous[i]
            previous = current
            current = next
        }
        return current
    }

    /**
     * The poles of the delay-normalised Bessel low-pass of an even [order] (the roots of `theta_order`), the ones with
     * a positive imaginary part, by Durand-Kerner iteration from fixed starting points, so the result is deterministic.
     */
    internal fun delayNormalisedPoles(order: Int): List<Pole> {
        require(order >= 2 && order % 2 == 0) { "order must be even and at least 2: $order" }
        val coefficients = besselPolynomial(order)
        val re = DoubleArray(order)
        val im = DoubleArray(order)
        // Starting points on a spiral: distinct and not symmetric about the real axis.
        var startRe = 1.0
        var startIm = 0.0
        for (k in 0 until order) {
            re[k] = startRe
            im[k] = startIm
            val nextRe = startRe * 0.4 - startIm * 0.9
            startIm = startRe * 0.9 + startIm * 0.4
            startRe = nextRe
        }
        repeat(ROOT_ITERATIONS) {
            var largestStep = 0.0
            for (k in 0 until order) {
                // p(z_k) by Horner.
                var pr = 0.0
                var pi = 0.0
                for (c in order downTo 0) {
                    val nr = pr * re[k] - pi * im[k] + coefficients[c]
                    val ni = pr * im[k] + pi * re[k]
                    pr = nr
                    pi = ni
                }
                // Divide by prod (z_k - z_j), j != k.
                var dr = 1.0
                var di = 0.0
                for (j in 0 until order) {
                    if (j == k) continue
                    val er = re[k] - re[j]
                    val ei = im[k] - im[j]
                    val nr = dr * er - di * ei
                    di = dr * ei + di * er
                    dr = nr
                }
                val norm = dr * dr + di * di
                val stepRe = (pr * dr + pi * di) / norm
                val stepIm = (pi * dr - pr * di) / norm
                re[k] -= stepRe
                im[k] -= stepIm
                largestStep = maxOf(largestStep, hypot(stepRe, stepIm))
            }
            if (largestStep < ROOT_TOLERANCE) return upperHalfPlane(re, im, order)
        }
        return upperHalfPlane(re, im, order)
    }

    private fun upperHalfPlane(re: DoubleArray, im: DoubleArray, order: Int): List<Pole> {
        val poles = (0 until order).filter { im[it] > 0 }.map { Pole(re[it], im[it]) }.sortedBy { it.im }
        check(poles.size == order / 2) { "Expected ${order / 2} conjugate pairs, found ${poles.size}" }
        return poles
    }

    /** The angular frequency (rad/s) at which the delay-normalised Bessel low-pass of [order] is down 3 dB. */
    internal fun threeDbFrequency(order: Int): Double {
        val c = besselPolynomial(order)
        val target = 2 * c[0] * c[0]
        // |theta(jw)|^2 grows with w, from c0^2 at DC.
        var low = 0.0
        var high = 4.0 * order
        check(squaredMagnitude(c, high) > target) { "Bisection bracket too small for order $order" }
        repeat(BISECTION_STEPS) {
            val middle = (low + high) / 2
            if (squaredMagnitude(c, middle) > target) high = middle else low = middle
        }
        return (low + high) / 2
    }

    /** `|theta(jw)|^2` for the polynomial with coefficients [c] (constant term first). */
    private fun squaredMagnitude(c: DoubleArray, w: Double): Double {
        var re = 0.0
        var im = 0.0
        var power = 1.0
        for (k in c.indices) {
            when (k % 4) {
                0 -> re += c[k] * power
                1 -> im += c[k] * power
                2 -> re -= c[k] * power
                else -> im -= c[k] * power
            }
            power *= w
        }
        return re * re + im * im
    }

    /** The prototype poles (upper half plane) scaled to a -3 dB point at 1 rad/s. */
    internal fun prototypePoles(order: Int): List<Pole> {
        val scale = threeDbFrequency(order)
        return delayNormalisedPoles(order).map { Pole(it.re / scale, it.im / scale) }
    }

    /** A [ORDER]th-order Bessel low-pass, -3 dB at [frequencyHz]. */
    fun lowPass(frequencyHz: Double, sampleRateHz: Int): BiquadCascade {
        val k = prewarped(frequencyHz, sampleRateHz)
        val poles = prototypePoles(ORDER)
        val section = Sections(poles.size)
        for ((i, pole) in poles.withIndex()) {
            val s0 = pole.re
            val p2 = pole.re * pole.re + pole.im * pole.im
            val a0 = 1 - 2 * s0 * k + p2 * k * k
            val gain = p2 * k * k / a0
            section.set(i, gain, 2 * gain, gain, 2 * (p2 * k * k - 1) / a0, (1 + 2 * s0 * k + p2 * k * k) / a0)
        }
        return section.build()
    }

    /** A [ORDER]th-order Bessel high-pass, -3 dB at [frequencyHz]. */
    fun highPass(frequencyHz: Double, sampleRateHz: Int): BiquadCascade {
        val k = prewarped(frequencyHz, sampleRateHz)
        val poles = prototypePoles(ORDER)
        val section = Sections(poles.size)
        for ((i, pole) in poles.withIndex()) {
            val s0 = pole.re
            val p2 = pole.re * pole.re + pole.im * pole.im
            val a0 = k * k - 2 * s0 * k + p2
            val gain = p2 / a0
            section.set(i, gain, -2 * gain, gain, 2 * (k * k - p2) / a0, (k * k + 2 * s0 * k + p2) / a0)
        }
        return section.build()
    }

    /** A band-pass: a high-pass at [lowHz] followed by a low-pass at [highHz], each -3 dB at its edge. */
    fun bandPass(lowHz: Double, highHz: Double, sampleRateHz: Int): BiquadCascade {
        require(lowHz < highHz) { "lowHz ($lowHz) must be below highHz ($highHz)" }
        return highPass(lowHz, sampleRateHz) + lowPass(highHz, sampleRateHz)
    }

    /** `K = tan(pi f / fs)`, the pre-warped cutoff of the bilinear transform. */
    private fun prewarped(frequencyHz: Double, sampleRateHz: Int): Double {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(frequencyHz.isFinite() && frequencyHz > 0 && frequencyHz < sampleRateHz / 2.0) {
            "frequencyHz must be between 0 and Nyquist ($sampleRateHz / 2): $frequencyHz"
        }
        return tan(PI * frequencyHz / sampleRateHz)
    }

    private class Sections(size: Int) {
        private val b0 = DoubleArray(size)
        private val b1 = DoubleArray(size)
        private val b2 = DoubleArray(size)
        private val a1 = DoubleArray(size)
        private val a2 = DoubleArray(size)

        fun set(i: Int, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
            this.b0[i] = b0
            this.b1[i] = b1
            this.b2[i] = b2
            this.a1[i] = a1
            this.a2[i] = a2
        }

        fun build() = BiquadCascade(b0, b1, b2, a1, a2)
    }
}
