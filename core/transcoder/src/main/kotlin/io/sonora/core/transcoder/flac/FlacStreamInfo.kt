package io.sonora.core.transcoder.flac

/**
 * A FLAC `STREAMINFO` block body (RFC 9639 section 8.2, 34 bytes, big-endian bit fields) as the platform encoder
 * states it in its codec-specific data, before it knows the stream's length.
 */
class FlacStreamInfo(body: ByteArray) {
    private val body = body.copyOf()

    init {
        require(body.size == BODY_BYTES) { "STREAMINFO is $BODY_BYTES bytes, not ${body.size}" }
    }

    /** 20 bits from byte 10. */
    val sampleRateHz: Int get() = (u8(10) shl 12) or (u8(11) shl 4) or (u8(12) ushr 4)

    /** 3 bits after the rate, plus one. */
    val channels: Int get() = ((u8(12) ushr 1) and 0x07) + 1

    /** 5 bits after the channels, plus one. */
    val bitsPerSample: Int get() = (((u8(12) and 0x01) shl 4) or (u8(13) ushr 4)) + 1

    /**
     * The body for the finished stream: [totalSamples] (frames per channel, 36 bits) patched in, the minimum and
     * maximum frame sizes and the MD5 signature set to 0 ("unknown"), since the encoder stated them before the end.
     */
    fun withTotalSamples(totalSamples: Long): ByteArray {
        require(totalSamples in 0 until (1L shl 36)) { "FLAC states at most 2^36 - 1 samples: $totalSamples" }
        val out = body.copyOf()
        out.fill(0, 4, 10)
        out[13] = ((out[13].toInt() and 0xF0) or (totalSamples ushr 32).toInt()).toByte()
        for (i in 0 until 4) out[14 + i] = (totalSamples ushr (24 - 8 * i)).toByte()
        out.fill(0, 18, BODY_BYTES)
        return out
    }

    private fun u8(index: Int) = body[index].toInt() and 0xFF

    companion object {
        const val BODY_BYTES = 34

        /**
         * The STREAMINFO body in an encoder's codec-specific data: behind the `fLaC` marker as its first metadata
         * block (what the platform encoder gives, possibly with more blocks after it), or the bare 34-byte body.
         * `null` when [config] is neither.
         */
        fun fromCodecConfig(config: ByteArray): ByteArray? {
            if (config.size == BODY_BYTES) return config.copyOf()
            val headed = config.size >= 8 + BODY_BYTES && FlacStreamWriter.MARKER.indices.all { config[it] == FlacStreamWriter.MARKER[it] }
            if (!headed) return null
            val type = config[4].toInt() and 0x7F
            val length = ((config[5].toInt() and 0xFF) shl 16) or ((config[6].toInt() and 0xFF) shl 8) or (config[7].toInt() and 0xFF)
            if (type != 0 || length != BODY_BYTES) return null
            return config.copyOfRange(8, 8 + BODY_BYTES)
        }
    }
}
