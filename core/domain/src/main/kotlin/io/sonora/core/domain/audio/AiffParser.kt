package io.sonora.core.domain.audio

import io.sonora.core.domain.audio.PcmLayout.SampleKind
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteOrder

/** A forward-only byte stream the [AiffParser] reads from: a file, a SAF descriptor or a player's extractor input. */
interface AiffSource {
    /** Reads exactly [length] bytes into [target] at [offset]; throws [EOFException] if the stream ends first. */
    fun readFully(target: ByteArray, offset: Int, length: Int)

    /** Skips exactly [byteCount] bytes; throws [EOFException] if the stream ends first. */
    fun skipFully(byteCount: Long)

    companion object {
        fun of(stream: InputStream): AiffSource = object : AiffSource {
            override fun readFully(target: ByteArray, offset: Int, length: Int) {
                var done = 0
                while (done < length) {
                    val read = stream.read(target, offset + done, length - done)
                    if (read < 0) throw EOFException()
                    done += read
                }
            }

            override fun skipFully(byteCount: Long) {
                var left = byteCount
                while (left > 0) {
                    val skipped = stream.skip(left)
                    if (skipped > 0) {
                        left -= skipped
                    } else if (stream.read() < 0) {
                        throw EOFException()
                    } else {
                        left--
                    }
                }
            }
        }
    }
}

/**
 * Reads the header of an AIFF or AIFF-C stream (Apple's Audio IFF 1.3 and AIFF-C draft): the FORM header, COMM
 * (channels, frames, sample size, 80-bit extended sample rate, AIFF-C compression) and the SSND data position.
 * Other chunks (ID3 with artwork, MARK, COMT, APPL, ...) are skipped, odd-sized ones with their pad byte, and the
 * chunks may come in any order. Supported sample formats: integer PCM 1-32 bits (big-endian `NONE`/`twos`/`in24`/
 * `in32`, little-endian `sowt`), offset-binary 8-bit `raw `, and big-endian float `fl32`/`FL32`/`fl64`/`FL64`.
 *
 * Sample rates above [MAX_SAMPLE_RATE_HZ] and more than [MAX_CHANNELS] channels are rejected as unsupported.
 *
 * Pure Kotlin so the player's extractor and the converter share it. The parser stops right after the SSND header
 * once COMM is known, so it never reads sound data; when SSND comes first it skips it, and the caller must seek
 * back to [AiffHeader.dataStart].
 */
object AiffParser {

    /** Whether [prefix] (at least the first 12 bytes of a stream) starts an AIFF or AIFF-C file. */
    fun isAiff(prefix: ByteArray): Boolean =
        prefix.size >= 12 && prefix.fourCc(0) == "FORM" && prefix.fourCc(8).let { it == "AIFF" || it == "AIFC" }

    /** Parses the header from the start of [source]; throws an [AiffException] when it is not playable PCM. */
    fun parse(source: AiffSource): AiffHeader = Reader(source).read()

    private class Common(val channels: Int, val frames: Long, val bits: Int, val rate: Double, val type: String, val name: String)

    private class Sound(val dataStart: Long, val availableBytes: Long?, val blockSize: Long)

    private class Reader(private val source: AiffSource) {
        private var position = 0L
        private val scratch = ByteArray(12)

        fun read(): AiffHeader {
            read(scratch, 12, "the FORM header")
            if (!isAiff(scratch)) throw AiffException.NotAiff()
            val isAifc = scratch.fourCc(8) == "AIFC"
            var common: Common? = null
            var sound: Sound? = null
            while (common == null || sound == null) {
                read(scratch, 8, if (common == null) "the header before COMM" else "the header before SSND")
                val id = scratch.fourCc(0)
                val size = scratch.uint32(4)
                val padded = size + (size and 1)
                when {
                    id == "COMM" && common == null -> {
                        val body = ByteArray(minOf(size, MAX_COMM_BYTES).toInt())
                        read(body, body.size, "the COMM chunk")
                        skip(padded - body.size, "the COMM chunk")
                        common = parseCommon(body, isAifc)
                    }
                    id == "SSND" && sound == null -> {
                        if (size < 8) throw AiffException.Malformed("SSND chunk of $size bytes")
                        read(scratch, 8, "the SSND chunk")
                        val offset = scratch.uint32(0)
                        val blockSize = scratch.uint32(4)
                        val available = (size - 8 - offset).takeIf { it >= 0 }
                        sound = Sound(dataStart = position + offset, availableBytes = available, blockSize = blockSize)
                        // Stop at the sound data once the format is known; otherwise look for COMM after it.
                        if (common == null) skip(padded - 8, "the SSND chunk")
                    }
                    else -> skip(padded, "the '$id' chunk")
                }
            }
            return header(isAifc, checkNotNull(common), checkNotNull(sound))
        }

        private fun header(isAifc: Boolean, common: Common, sound: Sound): AiffHeader {
            val layout = layoutOf(common)
            val bytesPerFrame = common.channels.toLong() * layout.bytesPerSample
            val wholeFramesInChunk = sound.availableBytes?.div(bytesPerFrame)
            val frames = when {
                common.frames == 0L -> wholeFramesInChunk ?: 0L
                wholeFramesInChunk != null -> minOf(common.frames, wholeFramesInChunk)
                else -> common.frames
            }
            return AiffHeader(
                isAifc = isAifc,
                channelCount = common.channels,
                frameCount = frames,
                declaredBits = common.bits,
                sampleRate = common.rate,
                compressionType = common.type,
                compressionName = common.name,
                layout = layout,
                dataStart = sound.dataStart,
                dataLength = frames * bytesPerFrame,
                blockSize = sound.blockSize,
            )
        }

        private fun read(target: ByteArray, length: Int, what: String) {
            try {
                source.readFully(target, 0, length)
            } catch (_: EOFException) {
                throw AiffException.Truncated(what)
            }
            position += length
        }

        private fun skip(byteCount: Long, what: String) {
            if (byteCount <= 0) return
            try {
                source.skipFully(byteCount)
            } catch (_: EOFException) {
                throw AiffException.Truncated(what)
            }
            position += byteCount
        }
    }

    private fun parseCommon(body: ByteArray, isAifc: Boolean): Common {
        val minimum = if (isAifc) 22 else 18
        if (body.size < minimum) throw AiffException.Malformed("COMM chunk of ${body.size} bytes")
        val channels = body.uint16(0)
        if (channels == 0) throw AiffException.Malformed("no channels")
        if (channels > MAX_CHANNELS) throw AiffException.UnsupportedChannelCount(channels)
        val rate = extendedToDouble(body, 8)
        if (!rate.isFinite() || rate < 1.0) throw AiffException.Malformed("sample rate $rate")
        if (rate > MAX_SAMPLE_RATE_HZ) throw AiffException.UnsupportedSampleRate(rate)
        val type = if (isAifc) body.fourCc(18) else "NONE"
        val name = if (isAifc && body.size > 22) {
            val length = minOf(body[22].toInt() and 0xFF, body.size - 23)
            String(body, 23, length, Charsets.ISO_8859_1)
        } else {
            "not compressed"
        }
        return Common(channels, body.uint32(2), body.uint16(6), rate, type, name)
    }

    private fun layoutOf(common: Common): PcmLayout {
        fun integer(order: ByteOrder): PcmLayout {
            if (common.bits !in 1..32) throw AiffException.UnsupportedSampleSize(common.bits, common.type)
            return PcmLayout(SampleKind.SIGNED_INT, (common.bits + 7) / 8 * 8, order)
        }
        return when (common.type) {
            "NONE", "twos" -> integer(ByteOrder.BIG_ENDIAN)
            "sowt" -> integer(ByteOrder.LITTLE_ENDIAN)
            "in24" -> PcmLayout(SampleKind.SIGNED_INT, 24, ByteOrder.BIG_ENDIAN)
            "in32" -> PcmLayout(SampleKind.SIGNED_INT, 32, ByteOrder.BIG_ENDIAN)
            "fl32", "FL32" -> PcmLayout(SampleKind.FLOAT, 32, ByteOrder.BIG_ENDIAN)
            "fl64", "FL64" -> PcmLayout(SampleKind.FLOAT, 64, ByteOrder.BIG_ENDIAN)
            "raw " -> {
                if (common.bits != 8) throw AiffException.UnsupportedSampleSize(common.bits, common.type)
                PcmLayout(SampleKind.UNSIGNED_INT, 8, ByteOrder.BIG_ENDIAN)
            }
            else -> throw AiffException.UnsupportedCompression(common.type, common.name)
        }
    }

    /** An 80-bit IEEE 754 extended float (sign, 15-bit exponent, 64-bit mantissa with explicit integer bit). */
    private fun extendedToDouble(bytes: ByteArray, at: Int): Double {
        val signAndExponent = bytes.uint16(at)
        var mantissa = 0L
        for (i in 0 until 8) mantissa = (mantissa shl 8) or (bytes[at + 2 + i].toLong() and 0xFF)
        val exponent = signAndExponent and 0x7FFF
        if (exponent == 0x7FFF) return Double.NaN
        if (mantissa == 0L) return 0.0
        // The top 53 mantissa bits fit a double exactly; the rest is below double precision anyway.
        val value = Math.scalb((mantissa ushr 11).toDouble(), exponent - 16_383 - 52)
        return if (signAndExponent and 0x8000 != 0) -value else value
    }

    /**
     * Highest sample rate accepted (DXD / 8x DSD-rate PCM tops out at 768 kHz). Beyond it a header is garbage or a
     * format nothing plays; the bound also keeps frame and byte-rate arithmetic far from overflow.
     */
    const val MAX_SAMPLE_RATE_HZ = 768_000

    /** Most channels accepted; real files carry at most a few dozen (Media3 and Android top out lower). */
    const val MAX_CHANNELS = 32

    /** Upper bound on the COMM bytes kept (18 or 22 plus a 255-character compression name). */
    private const val MAX_COMM_BYTES = 512L

    private fun ByteArray.fourCc(at: Int) = String(this, at, 4, Charsets.ISO_8859_1)

    private fun ByteArray.uint16(at: Int) = (this[at].toInt() and 0xFF shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.uint32(at: Int): Long =
        (uint16(at).toLong() shl 16) or uint16(at + 2).toLong()
}
