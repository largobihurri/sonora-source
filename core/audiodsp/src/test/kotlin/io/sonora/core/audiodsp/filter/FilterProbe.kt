package io.sonora.core.audiodsp.filter

import io.sonora.core.audiodsp.resample.Signals
import kotlin.math.abs
import kotlin.math.max

/** Steady-state measurements of a mono in-place processor with sines. */
object FilterProbe {

    private const val AMPLITUDE = 0.25

    /**
     * Gain in dB of [process] at [frequencyHz]: a sine of [amplitude] runs through it for a settling time, then its
     * amplitude is fitted over a window of whole periods.
     */
    fun gainDb(
        rateHz: Int,
        frequencyHz: Double,
        amplitude: Double = AMPLITUDE,
        process: (FloatArray) -> Unit,
    ): Double {
        val settle = max(rateHz / 2, (20 * rateHz / frequencyHz).toInt())
        val measure = max(rateHz / 4, (50 * rateHz / frequencyHz).toInt())
        val signal = Signals.sines(rateHz, settle + measure, amplitude, frequencyHz)
        process(signal)
        val out = Signals.channel(signal, channels = 1, index = 0)
        return Signals.db(Signals.amplitudeAt(out, settle, settle + measure, frequencyHz, rateHz) / amplitude)
    }

    /** Largest absolute second difference of `samples[from until to]`: a step in the signal shows up here at full size. */
    fun maxSecondDifference(samples: FloatArray, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double {
        var worst = 0.0
        var i = from + 2
        while (i < to) {
            val a = samples[(i - 2) * channels + channel].toDouble()
            val b = samples[(i - 1) * channels + channel].toDouble()
            val c = samples[i * channels + channel].toDouble()
            worst = max(worst, abs(c - 2 * b + a))
            i++
        }
        return worst
    }
}
