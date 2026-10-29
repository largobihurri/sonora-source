package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * MPEG audio (MP3 and MP2/MP1 elementary streams). The payload is everything from the first confirmed frame to the
 * last byte before the trailing tags: leading ID3v2 tags (every one, with the footer when flagged), junk before the
 * first frame, and ID3v1, `TAG+`, APEv2, Lyrics3v2 and appended ID3v2 tags at the end are excluded. The Xing/LAME
 * information frame is a normal frame and stays in.
 *
 * A frame header counts as the first frame only when a second valid header with the same version, layer and sample
 * rate sits exactly one frame length later, so a sync-looking byte pair inside junk is not taken for audio. Header
 * layout and frame-length formulas follow ISO/IEC 11172-3 and 13818-3; the tag layouts follow the ID3v2.4 structure
 * document, the APEv2 specification and the Lyrics3 v2.00 specification.
 */
object Mp3PayloadWalker : PayloadWalker {

    override val containerTag: String = "mp3"

    /** The most junk tolerated between the tags and the first frame. */
    private const val MAX_JUNK_BYTES = 1 shl 20

    private const val CHUNK_SIZE = 64 * 1024
    private const val HEADER_SIZE = 4

    private const val TAG_PLUS_SIZE = 227
    private const val APE_BLOCK_SIZE = 32
    private const val APE_HEADER_FLAG = 0x80000000L
    private const val LYRICS3_TRAILER_SIZE = 15 // six size digits + "LYRICS200"
    private const val ID3V2_FOOTER_SIZE = 10

    private val BITRATES_KBPS = arrayOf(
        // MPEG-1 layers I, II, III
        intArrayOf(32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
        intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
        intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
        // MPEG-2 and 2.5 layer I, then layers II and III
        intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    )

    /** Indexed by the version bits: 0 = MPEG-2.5, 2 = MPEG-2, 3 = MPEG-1. */
    private val SAMPLE_RATES = arrayOf(
        intArrayOf(11_025, 12_000, 8_000),
        intArrayOf(0, 0, 0),
        intArrayOf(22_050, 24_000, 16_000),
        intArrayOf(44_100, 48_000, 32_000),
    )

    override fun matches(prefix: ByteArray): Boolean =
        prefix.hasAscii(0, "ID3") || (prefix.size >= 2 && prefix.u8(0) == 0xFF && prefix.u8(1) and 0xE0 == 0xE0)

    override fun payloadRanges(source: ByteSource): List<LongRange>? {
        val length = source.length
        val tagsEnd = Id3Tags.skipLeadingTags(source, length)
        if (tagsEnd == Id3Tags.MALFORMED) return null
        val end = payloadEnd(source, tagsEnd, length)
        val start = findFirstFrame(source, tagsEnd, end)
        return if (start in 0 until end) listOf(start until end) else null
    }

    /** One decoded frame header: what must match between consecutive frames, and where the next one starts. */
    private class FrameHeader(val key: Int, val frameLength: Int)

    private fun parseHeader(bytes: ByteArray, index: Int): FrameHeader? {
        val b1 = bytes.u8(index + 1)
        val b2 = bytes.u8(index + 2)
        if (bytes.u8(index) != 0xFF || b1 and 0xE0 != 0xE0) return null
        val versionBits = (b1 shr 3) and 3
        val layerBits = (b1 shr 1) and 3
        val bitrateIndex = b2 shr 4
        val rateIndex = (b2 shr 2) and 3
        if (versionBits == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val layer = 4 - layerBits
        val mpeg1 = versionBits == 3
        val table = if (mpeg1) layer - 1 else if (layer == 1) 3 else 4
        val bitrate = BITRATES_KBPS[table][bitrateIndex - 1] * 1000
        val sampleRate = SAMPLE_RATES[versionBits][rateIndex]
        val padding = (b2 shr 1) and 1
        val length = when {
            layer == 1 -> (12 * bitrate / sampleRate + padding) * 4
            layer == 3 && !mpeg1 -> 72 * bitrate / sampleRate + padding
            else -> 144 * bitrate / sampleRate + padding
        }
        return FrameHeader(key = (versionBits shl 4) or (layer shl 2) or rateIndex, frameLength = length)
    }

    /** Position of the first frame at or after [from] and confirmed by its successor, or -1. */
    private fun findFirstFrame(source: ByteSource, from: Long, end: Long): Long {
        val buffer = ByteArray(CHUNK_SIZE)
        val limit = minOf(end, from + MAX_JUNK_BYTES)
        var position = from
        while (position + HEADER_SIZE <= limit) {
            val count = minOf(CHUNK_SIZE.toLong(), end - position).toInt()
            if (!source.readFully(position, buffer, count)) return -1
            for (i in 0..count - HEADER_SIZE) {
                if (position + i >= limit) break
                if (buffer.u8(i) != 0xFF || buffer.u8(i + 1) and 0xE0 != 0xE0) continue
                val first = parseHeader(buffer, i) ?: continue
                if (isConfirmed(source, position + i, first, end)) return position + i
            }
            if (count < CHUNK_SIZE) break
            position += count - (HEADER_SIZE - 1)
        }
        return -1
    }

    private fun isConfirmed(source: ByteSource, at: Long, first: FrameHeader, end: Long): Boolean {
        val next = at + first.frameLength
        if (next + HEADER_SIZE > end) return false
        val bytes = source.readBytes(next, HEADER_SIZE) ?: return false
        return parseHeader(bytes, 0)?.key == first.key
    }

    /** The end of the audio: [length] minus every trailing tag block, however they are stacked. */
    private fun payloadEnd(source: ByteSource, floor: Long, length: Long): Long {
        var end = length
        while (true) {
            val next = stripOneTrailer(source, floor, end)
            if (next == end) return end
            end = next
        }
    }

    private fun stripOneTrailer(source: ByteSource, floor: Long, end: Long): Long {
        if (Id3Tags.hasV1TagEndingAt(source, end, floor)) return end - Id3Tags.ID3V1_SIZE
        if (end - TAG_PLUS_SIZE >= floor && source.readBytes(end - TAG_PLUS_SIZE, 4)?.hasAscii(0, "TAG+") == true) {
            return end - TAG_PLUS_SIZE
        }
        apeStart(source, floor, end)?.let { return it }
        lyrics3Start(source, floor, end)?.let { return it }
        id3v2FooterStart(source, floor, end)?.let { return it }
        return end
    }

    /** Start of an APEv2 tag whose 32-byte footer ends at [end]: the header when the footer flags one, else the items. */
    private fun apeStart(source: ByteSource, floor: Long, end: Long): Long? {
        if (end - APE_BLOCK_SIZE < floor) return null
        val footer = source.readBytes(end - APE_BLOCK_SIZE, APE_BLOCK_SIZE) ?: return null
        if (!footer.hasAscii(0, "APETAGEX")) return null
        val tagSize = footer.u32le(12) // items and footer, not the header
        val hasHeader = footer.u32le(20) and APE_HEADER_FLAG != 0L
        if (tagSize < APE_BLOCK_SIZE) return null
        val total = tagSize + if (hasHeader) APE_BLOCK_SIZE else 0
        val start = end - total
        if (start < floor) return null
        if (hasHeader && source.readBytes(start, 8)?.hasAscii(0, "APETAGEX") != true) return null
        return start
    }

    /** Start of a Lyrics3v2 block ending at [end]: `LYRICSBEGIN`, fields, six size digits, `LYRICS200`. */
    private fun lyrics3Start(source: ByteSource, floor: Long, end: Long): Long? {
        if (end - LYRICS3_TRAILER_SIZE < floor) return null
        val trailer = source.readBytes(end - LYRICS3_TRAILER_SIZE, LYRICS3_TRAILER_SIZE) ?: return null
        if (!trailer.hasAscii(6, "LYRICS200")) return null
        var size = 0L
        for (i in 0 until 6) {
            val digit = trailer.u8(i) - '0'.code
            if (digit !in 0..9) return null
            size = size * 10 + digit
        }
        val start = end - LYRICS3_TRAILER_SIZE - size
        if (start < floor) return null
        return start.takeIf { source.readBytes(it, 11)?.hasAscii(0, "LYRICSBEGIN") == true }
    }

    /** Start of an ID3v2 tag appended to the file, found through its `3DI` footer that ends at [end]. */
    private fun id3v2FooterStart(source: ByteSource, floor: Long, end: Long): Long? {
        if (end - ID3V2_FOOTER_SIZE < floor) return null
        val footer = source.readBytes(end - ID3V2_FOOTER_SIZE, ID3V2_FOOTER_SIZE) ?: return null
        if (!footer.hasAscii(0, "3DI") || footer.u8(3) == 0xFF || footer.u8(4) == 0xFF) return null
        val size = Id3Tags.syncsafe(footer, 6) ?: return null
        val start = end - size - 2L * ID3V2_FOOTER_SIZE
        if (start < floor) return null
        return start.takeIf { source.readBytes(it, 3)?.hasAscii(0, "ID3") == true }
    }
}
