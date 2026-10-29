package io.sonora.core.audiodsp.fft

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radix-2 FFT of real signals of [size] samples (any power of two, 2 or more): [forward] gives the `size / 2 + 1`
 * non-redundant bins, [inverse] turns them back into samples (scaled so `inverse(forward(x))` is `x`).
 *
 * The real transform runs as a complex FFT of half the size plus one packing pass. Tables and scratch buffers are
 * built in the constructor, so a transform allocates nothing. An instance is not thread-safe: give each thread its own.
 * Arithmetic is in double precision; the arrays are floats, as the audio is.
 */
class RealFft(val size: Int) {

    /** Number of bins in a spectrum: `size / 2 + 1` (bin 0 is DC, the last one Nyquist; both have a zero imaginary part). */
    val binCount: Int

    private val half: Int
    private val bitReverse: IntArray

    // Twiddles of the half-size complex FFT: exp(-2 pi i k / half), k < half / 2.
    private val fftCos: DoubleArray
    private val fftSin: DoubleArray

    // Packing twiddles exp(-2 pi i k / size), k in 0..half.
    private val packCos: DoubleArray
    private val packSin: DoubleArray

    private val workRe: DoubleArray
    private val workIm: DoubleArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two of at least 2: $size" }
        half = size / 2
        binCount = half + 1
        val bits = Integer.numberOfTrailingZeros(half)
        bitReverse = IntArray(half) { if (bits == 0) 0 else Integer.reverse(it) ushr (32 - bits) }
        fftCos = DoubleArray(maxOf(half / 2, 1)) { cos(-2.0 * PI * it / half) }
        fftSin = DoubleArray(maxOf(half / 2, 1)) { sin(-2.0 * PI * it / half) }
        packCos = DoubleArray(half + 1) { cos(-2.0 * PI * it / size) }
        packSin = DoubleArray(half + 1) { sin(-2.0 * PI * it / size) }
        workRe = DoubleArray(half)
        workIm = DoubleArray(half)
    }

    /**
     * Transforms `input[inputOffset until inputOffset + size]` into [re] and [im], each [binCount] long.
     */
    fun forward(input: FloatArray, re: FloatArray, im: FloatArray, inputOffset: Int = 0) {
        require(inputOffset >= 0 && input.size - inputOffset >= size) { "input needs $size samples from $inputOffset" }
        require(re.size >= binCount && im.size >= binCount) { "re and im need $binCount bins" }
        // Pack even samples as the real and odd samples as the imaginary part of one half-size complex signal.
        for (k in 0 until half) {
            val target = bitReverse[k]
            workRe[target] = input[inputOffset + 2 * k].toDouble()
            workIm[target] = input[inputOffset + 2 * k + 1].toDouble()
        }
        butterflies(inverse = false)
        for (k in 0..half) {
            val a = k % half
            val b = (half - k) % half
            // Fe = (Z[k] + conj Z[half-k]) / 2, Fo = (Z[k] - conj Z[half-k]) / (2i); X[k] = Fe + W^k Fo.
            val feRe = (workRe[a] + workRe[b]) * 0.5
            val feIm = (workIm[a] - workIm[b]) * 0.5
            val foRe = (workIm[a] + workIm[b]) * 0.5
            val foIm = (workRe[b] - workRe[a]) * 0.5
            re[k] = (feRe + packCos[k] * foRe - packSin[k] * foIm).toFloat()
            im[k] = (feIm + packCos[k] * foIm + packSin[k] * foRe).toFloat()
        }
        // Rounding must not leak an imaginary part into the two real bins.
        im[0] = 0f
        im[half] = 0f
    }

    /**
     * Rebuilds `size` samples from the [binCount] bins [re] and [im] into `output[outputOffset until outputOffset + size]`.
     * The imaginary parts of the first and last bin are ignored.
     */
    fun inverse(re: FloatArray, im: FloatArray, output: FloatArray, outputOffset: Int = 0) {
        require(re.size >= binCount && im.size >= binCount) { "re and im need $binCount bins" }
        require(outputOffset >= 0 && output.size - outputOffset >= size) { "output needs room for $size samples from $outputOffset" }
        for (k in 0 until half) {
            val xRe = re[k].toDouble()
            val xIm = if (k == 0) 0.0 else im[k].toDouble()
            val yRe = re[half - k].toDouble()
            val yIm = if (k == 0) 0.0 else -im[half - k].toDouble() // conj X[half-k]; the last bin is real
            // Fe = (X[k] + conj X[half-k]) / 2, Fo = (X[k] - conj X[half-k]) / 2 * W^-k, Z[k] = Fe + i Fo.
            val feRe = (xRe + yRe) * 0.5
            val feIm = (xIm + yIm) * 0.5
            val dRe = (xRe - yRe) * 0.5
            val dIm = (xIm - yIm) * 0.5
            // Multiply d by W^-k = packCos - i packSin.
            val foRe = dRe * packCos[k] + dIm * packSin[k]
            val foIm = dIm * packCos[k] - dRe * packSin[k]
            val target = bitReverse[k]
            workRe[target] = feRe - foIm
            workIm[target] = feIm + foRe
        }
        butterflies(inverse = true)
        val scale = 1.0 / half
        for (k in 0 until half) {
            output[outputOffset + 2 * k] = (workRe[k] * scale).toFloat()
            output[outputOffset + 2 * k + 1] = (workIm[k] * scale).toFloat()
        }
    }

    /** In-place radix-2 FFT of the bit-reversed work arrays; the inverse uses the conjugate twiddles (unscaled). */
    private fun butterflies(inverse: Boolean) {
        val sign = if (inverse) -1.0 else 1.0
        var length = 2
        while (length <= half) {
            val halfLength = length / 2
            val step = half / length
            var start = 0
            while (start < half) {
                var twiddle = 0
                for (j in start until start + halfLength) {
                    val wr = fftCos[twiddle]
                    val wi = sign * fftSin[twiddle]
                    val k = j + halfLength
                    val tr = workRe[k] * wr - workIm[k] * wi
                    val ti = workRe[k] * wi + workIm[k] * wr
                    workRe[k] = workRe[j] - tr
                    workIm[k] = workIm[j] - ti
                    workRe[j] += tr
                    workIm[j] += ti
                    twiddle += step
                }
                start += length
            }
            length *= 2
        }
    }
}
