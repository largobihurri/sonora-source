package io.sonora.core.transcoder.ogg

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One `NAME=value` entry of a Vorbis comment header; [name] is printable ASCII without `=` (0x20..0x7D). */
data class VorbisComment(val name: String, val value: String) {
    init {
        require(name.isNotEmpty() && name.all { it in ' '..'}' && it != '=' }) { "Invalid Vorbis comment name: '$name'" }
    }
}

/**
 * The two header packets that start an Ogg Opus stream, laid out as RFC 7845 section 5 says: the identification
 * header `OpusHead` and the comment header `OpusTags` (the Vorbis comment format of the Vorbis I specification,
 * section 5.2, behind the `OpusTags` magic and without a framing bit).
 */
object OpusHeaders {

    private const val MAX_PRE_SKIP = 65_535

    /**
     * `OpusHead` version 1, channel mapping family 0 (mono or stereo). [preSkip] is the number of 48 kHz samples a
     * decoder drops from the start; [inputSampleRateHz] only informs (a decoder always outputs 48 kHz);
     * [outputGainQ78] is the header gain in Q7.8 dB, 0 for none.
     */
    fun head(channels: Int, preSkip: Int, inputSampleRateHz: Int, outputGainQ78: Int = 0): ByteArray {
        require(channels in 1..2) { "Mapping family 0 carries mono or stereo, not $channels channels" }
        require(preSkip in 0..MAX_PRE_SKIP) { "Pre-skip must fit 16 bits: $preSkip" }
        return ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put(HEAD_MAGIC)
            .put(1)
            .put(channels.toByte())
            .putShort(preSkip.toShort())
            .putInt(inputSampleRateHz)
            .putShort(outputGainQ78.toShort())
            .put(0)
            .array()
    }

    /** `OpusTags` with [vendor] (the encoder's name) and [comments], all UTF-8 with 32-bit little-endian lengths. */
    fun tags(vendor: String, comments: List<VorbisComment>): ByteArray = TAGS_MAGIC + comments(vendor, comments)

    /**
     * The Vorbis comment structure alone: [vendor], the count and [comments], UTF-8 with 32-bit little-endian
     * lengths and no framing bit. Behind `OpusTags` it is the Opus comment header; on its own it is the body of a
     * FLAC `VORBIS_COMMENT` metadata block.
     */
    fun comments(vendor: String, comments: List<VorbisComment>): ByteArray {
        val out = ByteArrayOutputStream()
        out.string(vendor)
        out.int(comments.size)
        for (comment in comments) out.string("${comment.name}=${comment.value}")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.int(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        int(bytes.size)
        write(bytes)
    }

    internal val HEAD_MAGIC = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val TAGS_MAGIC = "OpusTags".toByteArray(Charsets.US_ASCII)
}

/** What the table of contents byte of an Opus packet says about its length (RFC 6716 section 3.1). */
object OpusPackets {

    private const val MAX_PACKET_SAMPLES = 5_760 // 120 ms at 48 kHz

    /**
     * The audio in [packet] in samples at 48 kHz: the frame size its configuration gives times the number of frames
     * (one for code 0, two for codes 1 and 2, the count byte for code 3).
     *
     * @throws IllegalArgumentException when [packet] is empty, lacks its frame count or exceeds 120 ms.
     */
    fun sampleCount(packet: ByteArray): Int {
        require(packet.isNotEmpty()) { "An Opus packet has at least its TOC byte" }
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameSamples = when {
            config < 12 -> SILK_SAMPLES[config % 4]
            config < 16 -> HYBRID_SAMPLES[config % 2]
            else -> CELT_SAMPLES[config % 4]
        }
        val frames = when (toc and 0x03) {
            0 -> 1
            1, 2 -> 2
            else -> {
                require(packet.size >= 2) { "A code 3 packet carries its frame count in the second byte" }
                packet[1].toInt() and 0x3F
            }
        }
        require(frames >= 1) { "An Opus packet holds at least one frame" }
        val total = frames * frameSamples
        require(total <= MAX_PACKET_SAMPLES) { "An Opus packet holds at most 120 ms: $total samples" }
        return total
    }

    private val SILK_SAMPLES = intArrayOf(480, 960, 1_920, 2_880)
    private val HYBRID_SAMPLES = intArrayOf(480, 960)
    private val CELT_SAMPLES = intArrayOf(120, 240, 480, 960)
}

/** Where the pre-skip of a stream comes from. */
object OpusStreamConfig {

    /** libopus' look-ahead at 48 kHz (6.5 ms): the value the reference encoder writes. */
    const val DEFAULT_PRE_SKIP = 312

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val OPUS_RATE = 48_000L
    private const val HEAD_PRE_SKIP_OFFSET = 10

    /**
     * The pre-skip in 48 kHz samples: the one inside the encoder's own [opusHead] (its `csd-0`) when that is a valid
     * header, else the [codecDelayNs] it reports (`csd-1`), else [DEFAULT_PRE_SKIP].
     */
    fun preSkip(opusHead: ByteArray?, codecDelayNs: Long?): Int {
        if (opusHead != null && opusHead.size >= HEAD_PRE_SKIP_OFFSET + 2 &&
            (0 until OpusHeaders.HEAD_MAGIC.size).all { opusHead[it] == OpusHeaders.HEAD_MAGIC[it] }
        ) {
            return (opusHead[HEAD_PRE_SKIP_OFFSET].toInt() and 0xFF) or ((opusHead[HEAD_PRE_SKIP_OFFSET + 1].toInt() and 0xFF) shl 8)
        }
        if (codecDelayNs != null && codecDelayNs > 0) {
            return ((codecDelayNs * OPUS_RATE + NANOS_PER_SECOND / 2) / NANOS_PER_SECOND).toInt()
        }
        return DEFAULT_PRE_SKIP
    }
}
