package io.sonora.core.audiodsp.filter

/**
 * Linkwitz-Riley 24 dB/oct low-pass or high-pass: two cascaded second-order Butterworth sections (Q = 1/sqrt 2), so the
 * response is 6 dB down at the corner and a low-pass and a high-pass at the same corner sum to a flat (allpass)
 * magnitude, which makes it the crossover of the five-way preamp.
 *
 * The sections are trapezoidal state-variable filters (same response as bilinear biquads, pre-warped at the corner),
 * so the corner can move every sample without disturbing the state. Math is in double precision; the cutoff is given
 * in Hz with an explicit sample rate, so the response does not depend on the rate. Each channel keeps its own state.
 * Non-finite input is treated as silence. Not thread-safe: use it from the audio thread only.
 */
class LinkwitzRiley4(val type: Type, channels: Int = MAX_CHANNELS) {

    enum class Type { LOW_PASS, HIGH_PASS }

    init {
        require(channels in 1..MAX_CHANNELS) { "channels must be 1..$MAX_CHANNELS: $channels" }
    }

    private val coefficients = SvfCoefficients().apply { set(1000.0, BUTTERWORTH_Q, 48_000.0) }
    private val first = Array(channels) { SvfState() }
    private val second = Array(channels) { SvfState() }
    private val lowPass = type == Type.LOW_PASS

    /** Moves the corner; the state is kept, so this may be called every sample during a sweep. */
    fun setCutoff(cutoffHz: Double, sampleRateHz: Double) {
        coefficients.set(cutoffHz, BUTTERWORTH_Q, sampleRateHz)
    }

    /** Filters one sample of [channel]. */
    fun process(input: Double, channel: Int): Double {
        val x = if (input.isFinite()) input else 0.0
        return if (lowPass) {
            second[channel].lowPass(coefficients, first[channel].lowPass(coefficients, x))
        } else {
            second[channel].highPass(coefficients, first[channel].highPass(coefficients, x))
        }
    }

    fun reset() {
        for (s in first) s.reset()
        for (s in second) s.reset()
    }

    companion object {
        const val MAX_CHANNELS = 2
        internal const val BUTTERWORTH_Q = 0.7071067811865476
    }
}

/**
 * Second-order allpass with the Butterworth Q at [setCenter]: the phase a Linkwitz-Riley crossover at the same
 * frequency adds to the sum of its two outputs, used to phase-align the ways of the five-way preamp.
 */
internal class SecondOrderAllPass(channels: Int = LinkwitzRiley4.MAX_CHANNELS) {
    private val coefficients = SvfCoefficients()
    private val states = Array(channels) { SvfState() }

    init {
        setCenter(1000.0, 48_000.0)
    }

    fun setCenter(centerHz: Double, sampleRateHz: Double) {
        coefficients.set(centerHz, LinkwitzRiley4.BUTTERWORTH_Q, sampleRateHz)
    }

    fun process(input: Double, channel: Int): Double = states[channel].allPass(coefficients, input)

    fun reset() {
        for (s in states) s.reset()
    }
}
