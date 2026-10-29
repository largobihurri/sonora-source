package io.sonora.core.domain.playback.output

import java.nio.ByteBuffer

/**
 * Lossless PCM conversions for the bit-perfect output, on little-endian interleaved buffers (the byte order is read
 * and written byte by byte, so the buffers' own [java.nio.ByteOrder] does not matter). Only conversions that keep every
 * sample's value are supported: 16-bit to 24-bit packed, 32-bit or float; float holding 24-bit integers (what Media3's
 * sink makes of a 24-bit file) back to 24-bit packed or 32-bit; and copies.
 */
object PcmConverter {
    fun bytesPerSample(encoding: OutputEncoding): Int = when (encoding) {
        OutputEncoding.PCM_8 -> 1
        OutputEncoding.PCM_16 -> 2
        OutputEncoding.PCM_24_PACKED -> 3
        OutputEncoding.PCM_32, OutputEncoding.PCM_FLOAT -> 4
        OutputEncoding.OTHER -> 0
    }

    fun supports(from: OutputEncoding, to: OutputEncoding): Boolean = when (from) {
        OutputEncoding.PCM_16 -> to in WIDER_THAN_16 || to == OutputEncoding.PCM_16
        OutputEncoding.PCM_FLOAT -> to == OutputEncoding.PCM_FLOAT || to == OutputEncoding.PCM_24_PACKED || to == OutputEncoding.PCM_32
        else -> false
    }

    /**
     * Converts [frames] frames of [channels] channels from [input] (at its position) to [output] (at its position),
     * advancing both. Float samples are scaled by 2^23, rounded and clamped to the 24-bit range.
     *
     * @throws IllegalArgumentException when the conversion is not one [supports] lists.
     */
    fun convert(input: ByteBuffer, from: OutputEncoding, to: OutputEncoding, channels: Int, frames: Int, output: ByteBuffer) {
        require(supports(from, to)) { "Cannot convert $from to $to without losing samples" }
        val samples = frames * channels
        var read = input.position()
        when (from) {
            OutputEncoding.PCM_16 -> repeat(samples) {
                val lo = input.get(read).toInt() and 0xFF
                val hi = input.get(read + 1).toInt()
                read += 2
                when (to) {
                    OutputEncoding.PCM_16 -> output.put(lo.toByte()).put(hi.toByte())
                    OutputEncoding.PCM_24_PACKED -> output.put(0).put(lo.toByte()).put(hi.toByte())
                    OutputEncoding.PCM_32 -> output.put(0).put(0).put(lo.toByte()).put(hi.toByte())
                    else -> putFloat(output, ((hi shl 8) or lo) / 32_768f)
                }
            }
            else -> repeat(samples) {
                val bits = (input.get(read).toInt() and 0xFF) or
                    ((input.get(read + 1).toInt() and 0xFF) shl 8) or
                    ((input.get(read + 2).toInt() and 0xFF) shl 16) or
                    (input.get(read + 3).toInt() shl 24)
                read += 4
                when (to) {
                    OutputEncoding.PCM_FLOAT -> output.put(bits.toByte()).put((bits shr 8).toByte()).put((bits shr 16).toByte()).put((bits shr 24).toByte())
                    else -> {
                        val v = toInt24(Float.fromBits(bits))
                        if (to == OutputEncoding.PCM_24_PACKED) {
                            output.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
                        } else {
                            val left = v shl 8
                            output.put(left.toByte()).put((left shr 8).toByte()).put((left shr 16).toByte()).put((left shr 24).toByte())
                        }
                    }
                }
            }
        }
        input.position(read)
    }

    private val WIDER_THAN_16 = setOf(OutputEncoding.PCM_24_PACKED, OutputEncoding.PCM_32, OutputEncoding.PCM_FLOAT)

    /** [x] scaled to the signed 24-bit range, rounded, clamped (NaN is silence). */
    private fun toInt24(x: Float): Int {
        if (x.isNaN()) return 0
        val scaled = Math.rint(x * 8_388_608.0)
        return scaled.coerceIn(-8_388_608.0, 8_388_607.0).toInt()
    }

    private fun putFloat(output: ByteBuffer, value: Float) {
        val bits = value.toRawBits()
        output.put(bits.toByte()).put((bits shr 8).toByte()).put((bits shr 16).toByte()).put((bits shr 24).toByte())
    }
}
