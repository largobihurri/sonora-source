package io.sonora.core.djloop

/**
 * The sample encodings of the PCM the player's audio sink receives, channels interleaved. The byte order is part of the
 * encoding ([bigEndian]): Media3 decodes AIFF to big endian PCM and the loop sink sees it before `DefaultAudioSink`
 * converts it, so the engine reads and writes sample values in the stream's own order and copies bytes as they are.
 */
enum class PcmEncoding(val bytesPerSample: Int, val bigEndian: Boolean = false) {
    PCM_16(2),
    PCM_24(3),
    PCM_32(4),
    FLOAT_32(4),
    PCM_16_BE(2, bigEndian = true),
    PCM_24_BE(3, bigEndian = true),
    PCM_32_BE(4, bigEndian = true),
}

/** The layout of the PCM that goes through the loop engine. */
class PcmFormat(val sampleRate: Int, val channelCount: Int, val encoding: PcmEncoding) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
    }

    /** Bytes of one frame: one sample of every channel. */
    val frameBytes: Int = channelCount * encoding.bytesPerSample

    override fun toString(): String = "PcmFormat($sampleRate Hz, $channelCount ch, $encoding)"
}
