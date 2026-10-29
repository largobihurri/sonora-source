package io.sonora.core.djloop

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Test helper: reads and writes normalised (-1..1) samples of every encoding, in the encoding's own byte order. */
object PcmSamples {
    fun write(format: PcmFormat, data: ByteArray, frame: Int, channel: Int, value: Double) {
        val at = frame * format.frameBytes + channel * format.encoding.bytesPerSample
        val raw = when (format.encoding) {
            PcmEncoding.PCM_16, PcmEncoding.PCM_16_BE -> (value * 32768.0).roundToInt().coerceIn(-32768, 32767)
            PcmEncoding.PCM_24, PcmEncoding.PCM_24_BE -> (value * 8388608.0).roundToInt().coerceIn(-8388608, 8388607)
            PcmEncoding.PCM_32, PcmEncoding.PCM_32_BE ->
                (value * 2147483648.0).roundToLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            PcmEncoding.FLOAT_32 -> value.toFloat().toRawBits()
        }
        val n = format.encoding.bytesPerSample
        for (i in 0 until n) {
            val shift = 8 * (if (format.encoding.bigEndian) n - 1 - i else i)
            data[at + i] = (raw shr shift).toByte()
        }
    }

    fun read(format: PcmFormat, data: ByteArray, frame: Int, channel: Int): Double {
        val n = format.encoding.bytesPerSample
        val at = frame * format.frameBytes + channel * n
        var raw = 0
        for (i in 0 until n) {
            val shift = 8 * (if (format.encoding.bigEndian) n - 1 - i else i)
            raw = raw or ((data[at + i].toInt() and 0xFF) shl shift)
        }
        return when (format.encoding) {
            PcmEncoding.PCM_16, PcmEncoding.PCM_16_BE -> raw.toShort().toInt() / 32768.0
            PcmEncoding.PCM_24, PcmEncoding.PCM_24_BE -> ((raw shl 8) shr 8) / 8388608.0
            PcmEncoding.PCM_32, PcmEncoding.PCM_32_BE -> raw / 2147483648.0
            PcmEncoding.FLOAT_32 -> Float.fromBits(raw).toDouble()
        }
    }

    /** [data] with the bytes of every sample of [bytesPerSample] reversed: little endian <-> big endian. */
    fun swapped(data: ByteArray, bytesPerSample: Int): ByteArray {
        val out = ByteArray(data.size)
        for (at in data.indices step bytesPerSample) {
            for (i in 0 until bytesPerSample) out[at + i] = data[at + bytesPerSample - 1 - i]
        }
        return out
    }

    /** [frames] frames where every channel of frame `i` is `f(i)` (channel c gets `f(i) * (1 - 0.1 c)`). */
    fun signal(format: PcmFormat, frames: Int, f: (Int) -> Double): ByteArray {
        val data = ByteArray(frames * format.frameBytes)
        for (i in 0 until frames) for (c in 0 until format.channelCount) write(format, data, i, c, f(i) * (1 - 0.1 * c))
        return data
    }

    val ALL_ENCODINGS = PcmEncoding.entries
}
