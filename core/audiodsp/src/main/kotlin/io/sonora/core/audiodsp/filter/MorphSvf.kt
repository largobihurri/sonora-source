package io.sonora.core.audiodsp.filter

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The XY morph filter of the DUB stage: a resonant second-order state-variable filter on interleaved float audio.
 *
 * - X runs from low-pass (left, -1) through a flat detent (centre) to high-pass (right, +1). Outside the detent the
 *   cutoff follows a log map: the low-pass closes from 20 kHz to 200 Hz, the high-pass opens from 20 Hz to 6 kHz.
 * - Y is the resonance, Q 0.7 (Butterworth, 1/sqrt 2) to 8 on a log map.
 * - Inside the detent the filter is a true bypass: once the 10 ms crossfade has finished, the buffer is left
 *   untouched, bit for bit (non-finite samples, which never pass, are the only exception).
 * - X and Y glide with a 20 ms one-pole, so pad moves never step the coefficients. Switching between the low-pass and
 *   the high-pass side always passes through the bypass crossfade, never from one filter type straight to the other.
 * - The resonant node saturates softly, so a full-resonance sweep of loud input stays bounded; non-finite input is
 *   treated as silence.
 *
 * Coefficients are given in Hz and seconds with the sample rate of [prepare], so the response is the same at 44.1, 48
 * and 96 kHz. [prepare] allocates; [setPosition] and [process] allocate nothing and take no locks. Call them from the
 * audio thread only.
 */
class MorphSvf {

    private val coefficients = SvfCoefficients()
    private var states: Array<SvfState> = emptyArray()
    private var channels = 0
    private var sampleRate = 48_000.0

    private var targetX = 0.0
    private var targetY = 0.0
    private var x = 0.0
    private var y = 0.0
    private var glide = 1.0
    private var wet = 0.0
    private var wetStep = 1.0
    private var mode = NONE
    private var appliedAmount = Double.NaN
    private var appliedY = Double.NaN
    private var stateClean = true

    /** True while the filter is a bit-exact bypass (inside the detent, crossfade finished). */
    val isBypassed: Boolean get() = mode == NONE && wet == 0.0

    /** Sets the sample rate and channel count (1 or 2) and resets the filter to the current target, bypassed. */
    fun prepare(sampleRateHz: Int, channels: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(channels in 1..LinkwitzRiley4.MAX_CHANNELS) { "channels must be 1..2: $channels" }
        if (states.size != channels) states = Array(channels) { SvfState(NODE_KNEE) }
        this.channels = channels
        sampleRate = sampleRateHz.toDouble()
        glide = 1.0 - exp(-1.0 / (GLIDE_SECONDS * sampleRate))
        wetStep = 1.0 / max(1.0, CROSSFADE_SECONDS * sampleRate)
        reset()
    }

    /** X in -1..1 (low-pass .. detent .. high-pass), Y in 0..1 (resonance); out-of-range values are clamped. */
    fun setPosition(x: Float, y: Float) {
        targetX = if (x.isNaN()) 0.0 else x.toDouble().coerceIn(-1.0, 1.0)
        targetY = if (y.isNaN()) 0.0 else y.toDouble().coerceIn(0.0, 1.0)
    }

    /** Clears the state and jumps to the target position, starting from bypass. */
    fun reset() {
        for (s in states) s.reset()
        stateClean = true
        x = targetX
        y = targetY
        wet = 0.0
        mode = NONE
        appliedAmount = Double.NaN
        appliedY = Double.NaN
    }

    /** Filters `frames` interleaved frames of `buffer` in place, from `offset`. */
    fun process(buffer: FloatArray, offset: Int, frames: Int) {
        val ch = channels
        if (ch == 0) return
        val end = offset + frames * ch
        if (wet == 0.0 && sideOf(targetX) == NONE) {
            // True bypass: no filtering at all, only the non-finite guard.
            x = targetX
            y = targetY
            mode = NONE
            if (!stateClean) {
                for (s in states) s.reset()
                stateClean = true
            }
            for (i in offset until end) if (!buffer[i].isFinite()) buffer[i] = 0f
            return
        }
        stateClean = false
        var i = offset
        while (i < end) {
            advanceControls()
            val w = wet
            val active = mode
            for (c in 0 until ch) {
                val raw = buffer[i]
                val dry = if (raw.isFinite()) raw.toDouble() else 0.0
                if (active != NONE) {
                    val s = states[c]
                    var out = if (active == HIGH) s.highPass(coefficients, dry) else s.lowPass(coefficients, dry)
                    if (!out.isFinite()) {
                        s.reset()
                        out = dry
                    }
                    if (w != 0.0) buffer[i] = (dry + w * (out - dry)).toFloat() else if (!raw.isFinite()) buffer[i] = 0f
                } else if (!raw.isFinite()) {
                    buffer[i] = 0f
                }
                i++
            }
        }
    }

    private fun advanceControls() {
        if (x != targetX) {
            x += (targetX - x) * glide
            if (abs(targetX - x) < SNAP) x = targetX
        }
        if (y != targetY) {
            y += (targetY - y) * glide
            if (abs(targetY - y) < SNAP) y = targetY
        }
        val side = sideOf(x)
        if (mode == NONE && wet == 0.0 && side != NONE) {
            mode = side
            for (s in states) s.reset()
            appliedAmount = Double.NaN
        }
        val engaged = mode != NONE && side == mode
        wet = if (engaged) min(1.0, wet + wetStep) else max(0.0, wet - wetStep)
        if (wet == 0.0 && !engaged) mode = NONE
        if (mode == NONE) return
        // Off its own side the cutoff stays at the open end (20 kHz low-pass, 20 Hz high-pass) while it fades out.
        val amount = if (engaged) amountOf(x) else 0.0
        if (amount != appliedAmount || y != appliedY) {
            coefficients.set(cutoffFor(mode, amount), qFor(y), sampleRate)
            appliedAmount = amount
            appliedY = y
        }
    }

    companion object {
        /** Half width of the centre detent in X units: `|x| <= DETENT_HALF_WIDTH` is the bypass. */
        const val DETENT_HALF_WIDTH = 0.03f

        const val LOW_PASS_EDGE_HZ = 200.0
        const val HIGH_PASS_EDGE_HZ = 6000.0
        const val LOW_PASS_OPEN_HZ = 20_000.0
        const val HIGH_PASS_OPEN_HZ = 20.0
        const val MIN_Q = LinkwitzRiley4.BUTTERWORTH_Q
        const val MAX_Q = 8.0

        const val GLIDE_SECONDS = 0.020
        const val CROSSFADE_SECONDS = 0.010

        private const val NONE = 0
        private const val LOW = -1
        private const val HIGH = 1
        private const val SNAP = 1e-6
        private const val NODE_KNEE = 4.0

        /** Cutoff in Hz for X (low-pass for negative X, high-pass for positive X); NaN inside the detent. */
        fun cutoffHz(x: Float): Double {
            val side = sideOf(x.toDouble())
            return if (side == NONE) Double.NaN else cutoffFor(side, amountOf(x.toDouble()))
        }

        /** Resonance Q for Y in 0..1. */
        fun resonanceQ(y: Float): Double = qFor(y.toDouble().coerceIn(0.0, 1.0))

        private fun sideOf(x: Double): Int = when {
            x < -DETENT_HALF_WIDTH -> LOW
            x > DETENT_HALF_WIDTH -> HIGH
            else -> NONE
        }

        /** 0 at the detent edge, 1 at the end of the travel. */
        private fun amountOf(x: Double): Double =
            ((abs(x) - DETENT_HALF_WIDTH) / (1.0 - DETENT_HALF_WIDTH)).coerceIn(0.0, 1.0)

        private fun cutoffFor(side: Int, amount: Double): Double =
            if (side == LOW) {
                LOW_PASS_OPEN_HZ * (LOW_PASS_EDGE_HZ / LOW_PASS_OPEN_HZ).pow(amount)
            } else {
                HIGH_PASS_OPEN_HZ * (HIGH_PASS_EDGE_HZ / HIGH_PASS_OPEN_HZ).pow(amount)
            }

        private fun qFor(y: Double): Double = MIN_Q * exp(y * ln(MAX_Q / MIN_Q))
    }
}
