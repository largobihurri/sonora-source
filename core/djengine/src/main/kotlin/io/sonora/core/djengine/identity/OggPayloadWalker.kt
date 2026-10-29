package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * Ogg files with one logical stream of Vorbis, Opus or FLAC. The payload is the data of every packet after the
 * codec's header packets, in stream order. A packet is reassembled across pages from the lacing values (a segment of
 * 255 bytes continues the packet; anything shorter ends it), so the payload is the bytes of the page bodies that follow
 * the last header packet and the page headers, segment tables, sequence numbers, granule positions and checksums are
 * all excluded. A comment header that grows and moves the audio onto other pages therefore keeps the id.
 *
 * The codec is detected from the first packet, which fixes how many header packets to skip: Vorbis 3 (identification,
 * comment, setup), Opus 2 (`OpusHead`, `OpusTags`), FLAC 1 plus the count announced in its mapping header, or just 1
 * when the count is 0 and the STREAMINFO block carries the last-metadata-block flag. Any other codec (Speex, Theora,
 * Skeleton, ...) is not walked. The packet boundaries themselves are not hashed, only their bytes.
 *
 * Only a single logical stream is walked: a page of another serial number, which is what a multiplexed or a chained
 * file shows, makes the file malformed (`wf1`) rather than hashing the first stream and ignoring the rest. So do a
 * wrong capture pattern or stream structure version, a page that runs past the end of the file, continuation flags
 * that contradict the lacing, a stream that ends inside a packet or before its header packets, and a first page that
 * is not the beginning of the stream. Checksums and sequence numbers are not verified. The format follows RFC 3533
 * (Ogg), RFC 5334 (Ogg media types), the Vorbis I specification, RFC 7845 (Opus in Ogg) and the Ogg mapping of FLAC.
 *
 * It reads one page header (at most 282 bytes) per page and never the page bodies.
 */
object OggPayloadWalker : PayloadWalker {

    override val containerTag: String = "ogg"

    private const val PAGE_HEADER_SIZE = 27
    private const val MAX_SEGMENTS = 255
    private const val CONTINUED_FLAG = 0x01
    private const val FIRST_PAGE_FLAG = 0x02
    private const val FULL_SEGMENT = 255
    private const val IDENTIFICATION_PROBE = 16

    private const val VORBIS_HEADERS = 3
    private const val OPUS_HEADERS = 2

    override fun matches(prefix: ByteArray): Boolean = prefix.hasAscii(0, "OggS")

    override fun payloadRanges(source: ByteSource): List<LongRange>? {
        val length = source.length
        val page = ByteArray(PAGE_HEADER_SIZE + MAX_SEGMENTS)
        val ranges = ArrayList<LongRange>()
        var position = 0L
        var serial = 0L
        var headersLeft = -1
        var packetPending = false
        while (position < length) {
            val available = minOf(page.size.toLong(), length - position).toInt()
            if (available < PAGE_HEADER_SIZE || !source.readFully(position, page, available)) return null
            if (!page.hasAscii(0, "OggS") || page.u8(4) != 0) return null
            val flags = page.u8(5)
            val segments = page.u8(26)
            if (available < PAGE_HEADER_SIZE + segments) return null
            var bodySize = 0L
            for (i in 0 until segments) bodySize += page.u8(PAGE_HEADER_SIZE + i)
            val bodyStart = position + PAGE_HEADER_SIZE + segments
            if (bodyStart + bodySize > length) return null

            if (position == 0L) {
                if (flags and FIRST_PAGE_FLAG == 0 || flags and CONTINUED_FLAG != 0) return null
                serial = page.u32le(14)
                headersLeft = headerPacketCount(source, page, segments, bodyStart) ?: return null
            } else if (page.u32le(14) != serial || flags and FIRST_PAGE_FLAG != 0) {
                return null
            }
            if ((flags and CONTINUED_FLAG != 0) != packetPending) return null

            // Header packets come first, so once the last one ends everything after it in the stream is payload.
            var audioFrom = if (headersLeft == 0) 0L else -1L
            var offset = 0L
            for (i in 0 until segments) {
                val lacing = page.u8(PAGE_HEADER_SIZE + i)
                offset += lacing
                if (headersLeft > 0 && lacing < FULL_SEGMENT && --headersLeft == 0) audioFrom = offset
            }
            if (audioFrom in 0 until bodySize) ranges += (bodyStart + audioFrom) until (bodyStart + bodySize)
            if (segments > 0) packetPending = page.u8(PAGE_HEADER_SIZE + segments - 1) == FULL_SEGMENT
            position = bodyStart + bodySize
        }
        return if (packetPending || headersLeft != 0) null else ranges.ifEmpty { null }
    }

    /** How many packets at the start of the stream are headers, or `null` for a codec that is not walked. */
    private fun headerPacketCount(source: ByteSource, page: ByteArray, segments: Int, bodyStart: Long): Int? {
        var firstPacket = 0
        for (i in 0 until segments) {
            val lacing = page.u8(PAGE_HEADER_SIZE + i)
            firstPacket += lacing
            if (lacing < FULL_SEGMENT) break
        }
        val packet = source.readBytes(bodyStart, minOf(firstPacket, IDENTIFICATION_PROBE)) ?: return null
        return when {
            packet.size >= 7 && packet.u8(0) == 0x01 && packet.hasAscii(1, "vorbis") -> VORBIS_HEADERS
            packet.size >= 8 && packet.hasAscii(0, "OpusHead") -> OPUS_HEADERS
            packet.size >= 14 && packet.u8(0) == 0x7F && packet.hasAscii(1, "FLAC") && packet.u8(5) == 1 &&
                packet.hasAscii(9, "fLaC") -> flacHeaderPackets(packet)
            else -> null
        }
    }

    /** The mapping header says how many metadata packets follow; 0 means unknown unless STREAMINFO is the last block. */
    private fun flacHeaderPackets(packet: ByteArray): Int? {
        val following = (packet.u8(7) shl 8) or packet.u8(8)
        return when {
            following > 0 -> 1 + following
            packet.u8(13) and 0x80 != 0 -> 1
            else -> null
        }
    }
}
