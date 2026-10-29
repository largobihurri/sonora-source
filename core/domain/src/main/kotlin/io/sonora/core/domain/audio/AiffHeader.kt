package io.sonora.core.domain.audio

import java.io.IOException
import java.nio.ByteOrder

/** How one PCM sample is stored: integer or IEEE float, [bitsPerSample] bits (a whole number of bytes), [byteOrder]. */
data class PcmLayout(val kind: SampleKind, val bitsPerSample: Int, val byteOrder: ByteOrder) {
    enum class SampleKind {
        /** Two's complement integer. */
        SIGNED_INT,

        /** Offset binary integer (AIFF-C `raw `, 8-bit only). */
        UNSIGNED_INT,

        /** IEEE 754 float. */
        FLOAT,
    }

    val bytesPerSample: Int get() = bitsPerSample / 8
}

/**
 * The parts of an AIFF / AIFF-C file a player or converter needs (see [AiffParser]). Positions count bytes from
 * the start of the stream; the sound data is [dataLength] bytes of interleaved frames starting at [dataStart].
 */
data class AiffHeader(
    val isAifc: Boolean,
    val channelCount: Int,
    /** Sample frames in the sound data (the COMM count, or what the SSND chunk holds when COMM says 0). */
    val frameCount: Long,
    /** Sample size declared in COMM; integer samples narrower than [layout] are left-justified in it. */
    val declaredBits: Int,
    /** Frames per second, exactly as stored (AIFF allows fractional rates). */
    val sampleRate: Double,
    /** Four-character AIFF-C compression type; `NONE` for plain AIFF. */
    val compressionType: String,
    val compressionName: String,
    val layout: PcmLayout,
    val dataStart: Long,
    val dataLength: Long,
    /** SSND block size: the alignment the writer asked for, usually 0 (none). */
    val blockSize: Long,
) {
    val sampleRateHz: Int get() = Math.round(sampleRate).toInt()

    val bytesPerFrame: Int get() = channelCount * layout.bytesPerSample

    val durationUs: Long get() = frameCount * 1_000_000L / sampleRateHz
}

/** Why a stream could not be read as AIFF. */
sealed class AiffException(message: String) : IOException(message) {
    class NotAiff : AiffException("Not an AIFF or AIFF-C stream")

    /** The stream ended before the header and the start of the sound data were complete. */
    class Truncated(what: String) : AiffException("AIFF stream ends inside $what")

    class Malformed(detail: String) : AiffException("Malformed AIFF: $detail")

    /** Compressed AIFF-C (IMA ADPCM, µ-law, A-law, MACE, ...), which is not PCM. */
    class UnsupportedCompression(val type: String, val name: String) :
        AiffException("Unsupported AIFF-C compression '$type' ($name)")

    class UnsupportedSampleSize(val bits: Int, val compressionType: String) :
        AiffException("Unsupported AIFF sample size $bits bits for '$compressionType'")

    /** A sample rate above [AiffParser.MAX_SAMPLE_RATE_HZ]; no player or converter handles it. */
    class UnsupportedSampleRate(val rate: Double) : AiffException("Unsupported AIFF sample rate $rate Hz")

    /** More than [AiffParser.MAX_CHANNELS] channels. */
    class UnsupportedChannelCount(val channels: Int) : AiffException("Unsupported AIFF channel count $channels")
}
