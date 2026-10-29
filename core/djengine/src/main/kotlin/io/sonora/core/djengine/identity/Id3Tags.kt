package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/** ID3 tag arithmetic shared by the MP3 and FLAC walkers (ID3v2.2-2.4 headers, ID3v1). */
internal object Id3Tags {

    const val ID3V2_HEADER_SIZE = 10
    const val ID3V1_SIZE = 128

    private const val FOOTER_FLAG = 0x10

    /** Result of [skipLeadingTags] when a tag claims more bytes than the file holds. */
    const val MALFORMED = -1L

    /**
     * Size of the ID3v2 tag that starts at [position] (header, body, padding and the optional footer), `0` when no tag
     * starts there, or [MALFORMED] when the tag claims to extend beyond [length].
     */
    fun v2TagSize(source: ByteSource, position: Long, length: Long): Long {
        val header = ByteArray(ID3V2_HEADER_SIZE)
        if (position + ID3V2_HEADER_SIZE > length || !source.readFully(position, header)) return 0
        if (!header.hasAscii(0, "ID3") || header.u8(3) == 0xFF || header.u8(4) == 0xFF) return 0
        val size = syncsafe(header, 6) ?: return 0
        val total = ID3V2_HEADER_SIZE + size + if (header.u8(5) and FOOTER_FLAG != 0) ID3V2_HEADER_SIZE else 0
        return if (position + total > length) MALFORMED else total.toLong()
    }

    /** Position after every ID3v2 tag at the start of the file, or [MALFORMED]. */
    fun skipLeadingTags(source: ByteSource, length: Long): Long {
        var position = 0L
        while (true) {
            val size = v2TagSize(source, position, length)
            if (size < 0) return MALFORMED
            if (size == 0L) return position
            position += size
        }
    }

    /** Whether a 128-byte ID3v1 tag ends at [end] without reaching below [floor]. */
    fun hasV1TagEndingAt(source: ByteSource, end: Long, floor: Long): Boolean =
        end - ID3V1_SIZE >= floor && source.readBytes(end - ID3V1_SIZE, 3)?.hasAscii(0, "TAG") == true

    /** The 28-bit value of four syncsafe bytes at [index], or `null` when a byte has its high bit set. */
    fun syncsafe(bytes: ByteArray, index: Int): Int? {
        var value = 0
        for (i in 0 until 4) {
            val b = bytes.u8(index + i)
            if (b and 0x80 != 0) return null
            value = (value shl 7) or b
        }
        return value
    }
}
