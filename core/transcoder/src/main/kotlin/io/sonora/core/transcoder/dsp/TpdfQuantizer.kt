package io.sonora.core.transcoder.dsp

/**
 * Float `[-1, 1)` to signed [bits]-bit integers (16 or 24) with optional TPDF dither: the difference of two independent
 * uniform values adds triangular noise spanning ±1 LSB before rounding to the nearest step, which turns quantisation
 * error into signal-independent white noise (no harmonic distortion on quiet passages, fades and reverb tails). No
 * noise shaping. Overs clip to the output's range.
 *
 * The noise comes from a SplitMix64 generator seeded with [seed], so a conversion is reproducible bit for bit.
 * 16-bit output is computed in float (the XDJ-700 output depends on those exact bytes); 24-bit output in double,
 * since float cannot hold the half steps of a 24-bit grid. Not thread-safe.
 */
class TpdfQuantizer(seed: Long, private val dither: Boolean, val bits: Int = 16) {

    private var state = seed
    private val bytesPerSample = bits / 8
    private val min = -(1 shl (bits - 1))
    private val max = (1 shl (bits - 1)) - 1

    init {
        require(bits == 16 || bits == 24) { "Only 16- and 24-bit outputs: $bits" }
    }

    /** One sample as a [bits]-bit value; NaN counts as silence and infinities as full scale. */
    fun quantize(sample: Float): Int {
        // NaN would truncate to 0 below and land on the minimum (a full-scale click); -Inf would overflow to the maximum.
        val finite = if (sample.isNaN()) 0f else sample.coerceIn(-2f, 2f)
        if (bits == 24) {
            var scaled = finite * FULL_SCALE_24
            if (dither) scaled += triangular()
            return Math.floor(scaled + 0.5).toInt().coerceIn(min, max)
        }
        var scaled = finite * FULL_SCALE
        if (dither) scaled += triangular()
        // Adding 32768.5 makes every in-range value positive, so the truncating conversion rounds to nearest.
        return ((scaled + OFFSET).toInt() - 32_768).coerceIn(-32_768, 32_767)
    }

    /** Quantises [count] samples from [samples] at [from] into [target] as big-endian [bits]-bit integers from index 0. */
    fun quantizeToBigEndian(samples: FloatArray, count: Int, target: ByteArray, from: Int = 0) =
        quantizeTo(samples, from, count, target, bigEndian = true)

    /**
     * Quantises [count] samples from [samples] at [from] into [target] as little-endian [bits]-bit integers from index
     * 0 (WAV, codec input).
     */
    fun quantizeToLittleEndian(samples: FloatArray, count: Int, target: ByteArray, from: Int = 0) =
        quantizeTo(samples, from, count, target, bigEndian = false)

    /**
     * Quantises [count] samples into [target] as floats on the [bits]-bit grid (value / 2^(bits-1)): exact in float,
     * for codecs that take float input and keep [bits] bits of it.
     */
    fun quantizeToFloat(samples: FloatArray, count: Int, target: FloatArray, from: Int = 0) {
        require(from >= 0 && count >= 0 && from + count <= samples.size && count <= target.size) {
            "$count samples from $from do not fit ${samples.size} samples or ${target.size} targets"
        }
        val scale = 1f / (1 shl (bits - 1))
        for (i in 0 until count) target[i] = quantize(samples[from + i]) * scale
    }

    private fun quantizeTo(samples: FloatArray, from: Int, count: Int, target: ByteArray, bigEndian: Boolean) {
        require(from >= 0 && count >= 0 && from + count <= samples.size && count * bytesPerSample <= target.size) {
            "$count samples from $from do not fit ${samples.size} samples or ${target.size} bytes"
        }
        var at = 0
        for (i in 0 until count) {
            val value = quantize(samples[from + i])
            for (b in 0 until bytesPerSample) {
                val shift = if (bigEndian) 8 * (bytesPerSample - 1 - b) else 8 * b
                target[at + b] = (value shr shift).toByte()
            }
            at += bytesPerSample
        }
    }

    /** `u1 - u2` for two uniform values in [0, 1), from the two 24-bit halves of one 64-bit draw. */
    private fun triangular(): Float {
        val draw = next()
        val u1 = (draw ushr 40).toInt() * UNIT
        val u2 = ((draw ushr 8) and 0xFFFFFF).toInt() * UNIT
        return u1 - u2
    }

    private fun next(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    private companion object {
        const val FULL_SCALE = 32_768f
        const val OFFSET = 32_768.5f
        const val FULL_SCALE_24 = 8_388_608.0
        const val UNIT = 1f / (1 shl 24)
    }
}
