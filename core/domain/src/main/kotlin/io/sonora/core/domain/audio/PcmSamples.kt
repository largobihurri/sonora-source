package io.sonora.core.domain.audio

import io.sonora.core.domain.audio.PcmLayout.SampleKind
import java.nio.ByteOrder

/**
 * Sample conversions shared by every decode path (AIFF reader, platform decoders) and their consumers. Every result
 * lies in `[-1, 1)`: integer samples are divided by their container's full scale (so samples narrower than their
 * container, which AIFF and WAV left-justify, need no extra shift), and anything that would round to 1 or above,
 * such as full-scale 32-bit integers or float overs, is clipped to [MAX_BELOW_ONE].
 */
object PcmSamples {

    /** The largest float below 1: where full-scale positive samples land in `[-1, 1)`. */
    const val MAX_BELOW_ONE: Float = 0.99999994f

    private const val SCALE_8 = 1f / 128
    private const val SCALE_16 = 1f / 32_768
    private const val SCALE_24 = 1f / 8_388_608
    private const val SCALE_32 = 1.0 / 2_147_483_648.0

    /**
     * Converts [sampleCount] samples stored as [layout] from [source] at byte [sourceOffset] into [target] at
     * [targetOffset]. Float32 is exact for integer samples up to 24 bits; 32-bit integers lose their lowest 8 bits.
     *
     * @throws IndexOutOfBoundsException when either region does not fit its array.
     */
    fun toFloat(source: ByteArray, sourceOffset: Int, layout: PcmLayout, target: FloatArray, targetOffset: Int, sampleCount: Int) {
        val width = layout.bytesPerSample
        if (sampleCount < 0 || sourceOffset < 0 || targetOffset < 0 ||
            sourceOffset.toLong() + sampleCount.toLong() * width > source.size ||
            targetOffset.toLong() + sampleCount > target.size
        ) {
            throw IndexOutOfBoundsException(
                "$sampleCount samples of $width bytes from $sourceOffset (of ${source.size}) to $targetOffset (of ${target.size})",
            )
        }
        val little = layout.byteOrder == ByteOrder.LITTLE_ENDIAN
        var at = sourceOffset
        for (i in targetOffset until targetOffset + sampleCount) {
            target[i] = when (layout.kind) {
                SampleKind.UNSIGNED_INT -> ((source[at].toInt() and 0xFF) - 128) * SCALE_8
                SampleKind.SIGNED_INT -> when (width) {
                    1 -> source[at] * SCALE_8
                    2 -> signed(source, at, 2, little) * SCALE_16
                    3 -> signed(source, at, 3, little) * SCALE_24
                    4 -> clip((signed(source, at, 4, little) * SCALE_32).toFloat())
                    else -> throw IllegalArgumentException("Unsupported integer sample width: $width bytes")
                }
                SampleKind.FLOAT -> when (width) {
                    4 -> clip(Float.fromBits(signed(source, at, 4, little)))
                    8 -> clip(Double.fromBits(long(source, at, little)).toFloat())
                    else -> throw IllegalArgumentException("Unsupported float sample width: $width bytes")
                }
            }
            at += width
        }
    }

    /** Maps any float into `[-1, 1)`: overs clip to -1 or [MAX_BELOW_ONE], NaN becomes 0. */
    fun clip(value: Float): Float = when {
        value.isNaN() -> 0f
        value >= 1f -> MAX_BELOW_ONE
        value < -1f -> -1f
        else -> value
    }

    /**
     * Averages each of [frames] interleaved frames of [channelCount] samples into one mono sample in [target], from
     * index 0. [target] may be [interleaved] itself.
     */
    fun downmixToMono(interleaved: FloatArray, frames: Int, channelCount: Int, target: FloatArray) {
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        require(frames >= 0 && frames.toLong() * channelCount <= interleaved.size) {
            "$frames frames of $channelCount channels do not fit ${interleaved.size} samples"
        }
        require(frames <= target.size) { "$frames frames do not fit a target of ${target.size}" }
        if (channelCount == 1) {
            interleaved.copyInto(target, 0, 0, frames)
            return
        }
        val scale = 1f / channelCount
        for (frame in 0 until frames) {
            val start = frame * channelCount
            var sum = 0f
            for (channel in 0 until channelCount) sum += interleaved[start + channel]
            target[frame] = sum * scale
        }
    }

    /** A two's complement integer of [width] bytes, sign-extended to 32 bits. */
    private fun signed(bytes: ByteArray, at: Int, width: Int, little: Boolean): Int {
        var value = 0
        for (i in 0 until width) {
            val index = if (little) at + width - 1 - i else at + i
            value = (value shl 8) or (bytes[index].toInt() and 0xFF)
        }
        val unused = 32 - 8 * width
        return (value shl unused) shr unused
    }

    private fun long(bytes: ByteArray, at: Int, little: Boolean): Long {
        var value = 0L
        for (i in 0 until 8) {
            val index = if (little) at + 7 - i else at + i
            value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return value
    }
}

/** The source sample format a stored [PcmLayout] stands for. */
fun PcmLayout.toSampleFormat(): PcmSampleFormat = when (kind) {
    SampleKind.FLOAT -> if (bitsPerSample == 64) PcmSampleFormat.FLOAT64 else PcmSampleFormat.FLOAT32
    SampleKind.SIGNED_INT, SampleKind.UNSIGNED_INT -> when (bitsPerSample) {
        8 -> PcmSampleFormat.INT8
        16 -> PcmSampleFormat.INT16
        24 -> PcmSampleFormat.INT24
        else -> PcmSampleFormat.INT32
    }
}
