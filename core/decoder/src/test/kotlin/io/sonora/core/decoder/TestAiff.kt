package io.sonora.core.decoder

import java.io.ByteArrayOutputStream

/** Builds small AIFF / AIFF-C files in memory for the decoder tests. */
internal object TestAiff {

    /**
     * An AIFF (or AIFF-C when [compression] is set) with one COMM and one SSND chunk holding [data]. [frames] is the
     * COMM frame count; [ssndFirst] puts the sound data before COMM, which forces a reader to come back for it.
     */
    fun build(
        data: ByteArray,
        channels: Int,
        frames: Long,
        bits: Int,
        rate: Int = 44_100,
        compression: String? = null,
        ssndFirst: Boolean = false,
    ): ByteArray {
        val comm = ByteArrayOutputStream().apply {
            write(int16(channels))
            write(int32(frames))
            write(int16(bits))
            write(extended(rate))
            if (compression != null) {
                write(compression.toByteArray(Charsets.US_ASCII))
                write(byteArrayOf(0, 0)) // Empty Pascal name, padded to even length.
            }
        }.toByteArray()
        val ssnd = int32(0) + int32(0) + data
        val chunks = ByteArrayOutputStream()
        fun chunk(id: String, body: ByteArray) {
            chunks.write(id.toByteArray(Charsets.US_ASCII))
            chunks.write(int32(body.size.toLong()))
            chunks.write(body)
            if (body.size % 2 == 1) chunks.write(0)
        }
        if (compression != null) chunk("FVER", int32(0xA2805140))
        if (ssndFirst) {
            chunk("SSND", ssnd)
            chunk("COMM", comm)
        } else {
            chunk("COMM", comm)
            chunk("NAME", "tone".toByteArray(Charsets.US_ASCII))
            chunk("SSND", ssnd)
        }
        val content = chunks.toByteArray()
        val form = if (compression != null) "AIFC" else "AIFF"
        return "FORM".toByteArray(Charsets.US_ASCII) + int32(content.size + 4L) + form.toByteArray(Charsets.US_ASCII) + content
    }

    /** Big-endian 16-bit samples. */
    fun int16Be(vararg samples: Int): ByteArray =
        ByteArray(samples.size * 2).also { bytes -> samples.forEachIndexed { i, s -> int16(s).copyInto(bytes, 2 * i) } }

    fun int16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    fun int32(value: Long) =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    /** An integer sample rate as an 80-bit IEEE 754 extended float. */
    private fun extended(rate: Int): ByteArray {
        val highestBit = 31 - Integer.numberOfLeadingZeros(rate)
        val mantissa = rate.toLong() shl (63 - highestBit)
        return int16(16_383 + highestBit) + ByteArray(8) { (mantissa ushr (56 - 8 * it)).toByte() }
    }
}
