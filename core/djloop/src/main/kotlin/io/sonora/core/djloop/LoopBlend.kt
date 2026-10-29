package io.sonora.core.djloop

import kotlin.math.cos
import kotlin.math.sin

/**
 * The equal-power cross-fade that makes the wrap of a loop click free, for [format]'s encoding.
 *
 * A loop `[In, Out)` repeats as: for the first `blendFrames` frames of each repeat the audio after Out (`x[Out + j]`)
 * fades out while the audio after In (`x[In + j]`) fades in, then `x[In + blendFrames .. Out)`. So the last frame
 * before the wrap is `x[Out - 1]`, the first of the repeat is exactly `x[Out]` and the blend ends on `x[In + N]`:
 * the signal is continuous at both ends and the period is exactly the loop length.
 *
 * Gains are `cos` and `sin` of a quarter turn over the blend (constant power for uncorrelated audio); sums are rounded
 * and clamped in the integer encodings. Samples are read and written in the encoding's byte order. Nothing here allocates.
 */
class LoopBlend(val format: PcmFormat) {

    // Sample values are read and written in the stream's own byte order; the float encoding is little endian only.
    private val big = format.encoding.bigEndian

    /**
     * Writes [frames] frames to [dst] at [dstOffset] (bytes): the mix of [fadeOut] (at [fadeOutOffset], bytes) and
     * [fadeIn] for blend positions `firstIndex until firstIndex + frames` of a blend [blendFrames] long. At position 0
     * the result is [fadeOut]; the gain moves to [fadeIn] over the blend.
     */
    fun blend(
        fadeOut: ByteArray,
        fadeOutOffset: Int,
        fadeIn: ByteArray,
        fadeInOffset: Int,
        dst: ByteArray,
        dstOffset: Int,
        frames: Int,
        firstIndex: Int,
        blendFrames: Int,
    ) {
        val channels = format.channelCount
        var inAt = fadeInOffset
        var outAt = fadeOutOffset
        var at = dstOffset
        for (frame in 0 until frames) {
            val quarter = HALF_PI * (firstIndex + frame) / blendFrames
            val gainOut = cos(quarter)
            val gainIn = sin(quarter)
            for (channel in 0 until channels) {
                when (format.encoding) {
                    PcmEncoding.PCM_16, PcmEncoding.PCM_16_BE -> {
                        val mixed = pcm16(fadeOut, outAt) * gainOut + pcm16(fadeIn, inAt) * gainIn
                        putPcm16(dst, at, Math.round(mixed).toInt().coerceIn(-32768, 32767))
                        outAt += 2; inAt += 2; at += 2
                    }
                    PcmEncoding.PCM_24, PcmEncoding.PCM_24_BE -> {
                        val mixed = pcm24(fadeOut, outAt) * gainOut + pcm24(fadeIn, inAt) * gainIn
                        putPcm24(dst, at, Math.round(mixed).toInt().coerceIn(-8_388_608, 8_388_607))
                        outAt += 3; inAt += 3; at += 3
                    }
                    PcmEncoding.PCM_32, PcmEncoding.PCM_32_BE -> {
                        val mixed = pcm32(fadeOut, outAt) * gainOut + pcm32(fadeIn, inAt) * gainIn
                        putInt(dst, at, Math.round(mixed).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
                        outAt += 4; inAt += 4; at += 4
                    }
                    PcmEncoding.FLOAT_32 -> {
                        val mixed = float32(fadeOut, outAt) * gainOut + float32(fadeIn, inAt) * gainIn
                        putInt(dst, at, mixed.toFloat().toRawBits())
                        outAt += 4; inAt += 4; at += 4
                    }
                }
            }
        }
    }

    private fun pcm16(data: ByteArray, at: Int): Double =
        if (big) {
            ((data[at].toInt() shl 8) or (data[at + 1].toInt() and 0xFF)).toDouble()
        } else {
            ((data[at].toInt() and 0xFF) or (data[at + 1].toInt() shl 8)).toDouble()
        }

    private fun pcm24(data: ByteArray, at: Int): Double =
        if (big) {
            ((data[at].toInt() shl 16) or ((data[at + 1].toInt() and 0xFF) shl 8) or (data[at + 2].toInt() and 0xFF)).toDouble()
        } else {
            ((data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or (data[at + 2].toInt() shl 16)).toDouble()
        }

    private fun pcm32(data: ByteArray, at: Int): Double = readInt(data, at).toDouble()

    private fun float32(data: ByteArray, at: Int): Double = Float.fromBits(readInt(data, at)).toDouble()

    private fun readInt(data: ByteArray, at: Int): Int =
        if (big) {
            ((data[at].toInt() shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
                ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF))
        } else {
            (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
                ((data[at + 2].toInt() and 0xFF) shl 16) or (data[at + 3].toInt() shl 24)
        }

    private fun putPcm16(data: ByteArray, at: Int, value: Int) {
        if (big) {
            data[at] = (value shr 8).toByte()
            data[at + 1] = value.toByte()
        } else {
            data[at] = value.toByte()
            data[at + 1] = (value shr 8).toByte()
        }
    }

    private fun putPcm24(data: ByteArray, at: Int, value: Int) {
        if (big) {
            data[at] = (value shr 16).toByte()
            data[at + 1] = (value shr 8).toByte()
            data[at + 2] = value.toByte()
        } else {
            data[at] = value.toByte()
            data[at + 1] = (value shr 8).toByte()
            data[at + 2] = (value shr 16).toByte()
        }
    }

    private fun putInt(data: ByteArray, at: Int, value: Int) {
        if (big) {
            data[at] = (value shr 24).toByte()
            data[at + 1] = (value shr 16).toByte()
            data[at + 2] = (value shr 8).toByte()
            data[at + 3] = value.toByte()
        } else {
            data[at] = value.toByte()
            data[at + 1] = (value shr 8).toByte()
            data[at + 2] = (value shr 16).toByte()
            data[at + 3] = (value shr 24).toByte()
        }
    }

    companion object {
        /** Length of the cross-fade in milliseconds. */
        const val BLEND_MS: Double = 2.5

        /** The blend length for a loop of [loopFrames] at [sampleRate]: [BLEND_MS], at most a quarter of the loop. */
        fun blendFrames(sampleRate: Int, loopFrames: Long): Int =
            minOf(Math.round(BLEND_MS * sampleRate / 1000.0), loopFrames / 4).toInt()

        private const val HALF_PI = Math.PI / 2
    }
}
