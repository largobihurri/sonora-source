package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * FLAC native streams. The payload is the frame data: a leading ID3v2 tag, the `fLaC` marker and every metadata block
 * (STREAMINFO included, so a rewritten header does not change the id) are skipped, and so is a trailing ID3v1 tag.
 * The stream layout follows the FLAC format specification (RFC 9639): 4-byte block headers with a last-block flag, a
 * 7-bit type and a 24-bit length; the first frame starts with the 14-bit sync code 0b11111111111110.
 */
object FlacPayloadWalker : PayloadWalker {

    override val containerTag: String = "flac"

    private const val BLOCK_HEADER_SIZE = 4
    private const val LAST_BLOCK_FLAG = 0x80
    private const val INVALID_BLOCK_TYPE = 127

    override fun matches(prefix: ByteArray): Boolean = prefix.hasAscii(0, "fLaC") || prefix.hasAscii(0, "ID3")

    override fun payloadRanges(source: ByteSource): List<LongRange>? {
        val length = source.length
        val marker = Id3Tags.skipLeadingTags(source, length)
        if (marker == Id3Tags.MALFORMED || source.readBytes(marker, 4)?.hasAscii(0, "fLaC") != true) return null
        var position = marker + 4
        val header = ByteArray(BLOCK_HEADER_SIZE)
        while (true) {
            if (!source.readFully(position, header)) return null
            if (header.u8(0) and 0x7F == INVALID_BLOCK_TYPE) return null
            val blockSize = (header.u8(1) shl 16) or (header.u8(2) shl 8) or header.u8(3)
            position += BLOCK_HEADER_SIZE + blockSize
            if (position > length) return null
            if (header.u8(0) and LAST_BLOCK_FLAG != 0) break
        }
        val sync = source.readBytes(position, 2) ?: return null
        if (sync.u8(0) != 0xFF || sync.u8(1) and 0xFE != 0xF8) return null
        val end = if (Id3Tags.hasV1TagEndingAt(source, length, position)) length - Id3Tags.ID3V1_SIZE else length
        return if (end > position) listOf(position until end) else null
    }
}
