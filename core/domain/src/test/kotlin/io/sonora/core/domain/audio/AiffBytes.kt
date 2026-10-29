package io.sonora.core.domain.audio

import java.io.ByteArrayOutputStream

/** Builds synthetic AIFF / AIFF-C streams for tests, chunk by chunk. */
internal class AiffBytes(private val formType: String = "AIFF") {

    private val chunks = ByteArrayOutputStream()

    /** Adds a chunk with [body], plus the pad byte an odd-sized chunk needs. */
    fun chunk(id: String, body: ByteArray, declaredSize: Long = body.size.toLong()): AiffBytes = apply {
        chunks.write(id.toByteArray(Charsets.US_ASCII))
        chunks.write(int32(declaredSize))
        chunks.write(body)
        if (body.size % 2 == 1) chunks.write(0)
    }

    fun comm(
        channels: Int = 2,
        frames: Long = 4,
        bits: Int = 16,
        rate: ByteArray = extended(44_100),
        compression: String? = null,
        compressionName: String = "",
    ): AiffBytes {
        val body = ByteArrayOutputStream()
        body.write(int16(channels))
        body.write(int32(frames))
        body.write(int16(bits))
        body.write(rate)
        if (compression != null) {
            body.write(compression.toByteArray(Charsets.US_ASCII))
            body.write(pascal(compressionName))
        }
        return chunk("COMM", body.toByteArray())
    }

    fun ssnd(data: ByteArray, offset: Int = 0, blockSize: Int = 0, declaredSize: Long? = null): AiffBytes {
        val body = int32(offset.toLong()) + int32(blockSize.toLong()) + ByteArray(offset) + data
        return chunk("SSND", body, declaredSize ?: body.size.toLong())
    }

    fun build(): ByteArray {
        val content = chunks.toByteArray()
        return "FORM".toByteArray(Charsets.US_ASCII) + int32(content.size + 4L) +
            formType.toByteArray(Charsets.US_ASCII) + content
    }

    companion object {
        fun int16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        fun int32(value: Long) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        /** A Pascal string padded to an even total length, as AIFF-C stores the compression name. */
        fun pascal(text: String): ByteArray {
            val bytes = byteArrayOf(text.length.toByte()) + text.toByteArray(Charsets.US_ASCII)
            return if (bytes.size % 2 == 1) bytes + 0 else bytes
        }

        /** An integer sample rate as an 80-bit IEEE 754 extended float (explicit integer bit). */
        fun extended(rate: Int): ByteArray {
            val highestBit = 31 - Integer.numberOfLeadingZeros(rate)
            val exponent = 16_383 + highestBit
            val mantissa = rate.toLong() shl (63 - highestBit)
            return int16(exponent) + ByteArray(8) { (mantissa ushr (56 - 8 * it)).toByte() }
        }

        fun hex(text: String): ByteArray = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
    }
}
