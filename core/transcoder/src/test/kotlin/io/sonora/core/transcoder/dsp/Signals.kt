package io.sonora.core.transcoder.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Test signals and measurements for the DSP stages. */
object Signals {

    /** [frames] frames of [channels] interleaved channels; channel `c` carries a sine of `frequenciesHz[c]` (0 = silence). */
    fun sines(rateHz: Int, frames: Int, amplitude: Double, vararg frequenciesHz: Double): FloatArray {
        val channels = frequenciesHz.size
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val hz = frequenciesHz[c]
                out[f * channels + c] = if (hz == 0.0) 0f else (amplitude * sin(2 * PI * hz * f / rateHz)).toFloat()
            }
        }
        return out
    }

    fun channel(interleaved: FloatArray, channels: Int, index: Int): DoubleArray =
        DoubleArray(interleaved.size / channels) { interleaved[it * channels + index].toDouble() }

    /** Least-squares amplitude of a sine at [frequencyHz] in `samples[from until to]`: exact for any length. */
    fun amplitudeAt(samples: DoubleArray, from: Int, to: Int, frequencyHz: Double, rateHz: Int): Double {
        var ss = 0.0
        var cc = 0.0
        var sc = 0.0
        var ys = 0.0
        var yc = 0.0
        for (n in from until to) {
            val w = 2 * PI * frequencyHz * n / rateHz
            val s = sin(w)
            val c = cos(w)
            ss += s * s
            cc += c * c
            sc += s * c
            ys += samples[n] * s
            yc += samples[n] * c
        }
        val det = ss * cc - sc * sc
        val a = (ys * cc - yc * sc) / det
        val b = (yc * ss - ys * sc) / det
        return sqrt(a * a + b * b)
    }

    /** Frequency from the interpolated positions of the first and last rising zero crossings in `samples[from until to]`. */
    fun zeroCrossingFrequency(samples: DoubleArray, from: Int, to: Int, rateHz: Int): Double {
        var first = Double.NaN
        var last = Double.NaN
        var count = 0
        for (n in from + 1 until to) {
            val a = samples[n - 1]
            val b = samples[n]
            if (a < 0 && b >= 0) {
                val t = n - 1 + a / (a - b)
                if (count == 0) first = t
                last = t
                count++
            }
        }
        return (count - 1) * rateHz / (last - first)
    }

    fun rms(samples: DoubleArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (n in from until to) sum += samples[n] * samples[n]
        return sqrt(sum / (to - from))
    }

    fun db(ratio: Double): Double = 20 * log10(abs(ratio))
}
