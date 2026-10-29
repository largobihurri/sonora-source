package io.sonora.core.rekordbox.pdb

/**
 * The DeviceSQL string forms `export.pdb` stores text in:
 * - short ASCII, up to 126 bytes: one byte `(length + 1) << 1 | 1` (the whole field, header included), then the
 *   bytes; the empty string is the single byte `0x03`;
 * - long ASCII: `0x40`, u16 total length (content + 4), `0x00`, the bytes;
 * - anything with a non-ASCII character: `0x90`, u16 total length, `0x00`, UTF-16LE. Inside rows with string offsets
 *   rekordbox starts these on a 4-byte boundary of the row (see [isUtf16]);
 * - ISRC codes, a quirk of rekordbox: `0x90`, u16 total length, `0x00`, `0x03`, the ASCII code, `0x00`.
 */
object DeviceSqlString {
    private const val MAX_SHORT_ASCII = 126
    private const val LONG_HEADER_BYTES = 4
    private const val MAX_LONG_BYTES = Short.MAX_VALUE.toInt()
    private const val LONG_ASCII = 0x40
    private const val LONG_UTF16 = 0x90
    private const val ISRC_LENGTH = 12

    fun encode(text: String): ByteArray = when {
        isUtf16(text) -> long(LONG_UTF16, text.toByteArray(Charsets.UTF_16LE))
        text.length > MAX_SHORT_ASCII -> long(LONG_ASCII, text.toByteArray(Charsets.US_ASCII))
        else -> littleEndian {
            u8((text.length + 1) shl 1 or 1)
            bytes(text.toByteArray(Charsets.US_ASCII))
        }
    }

    /** Whether [text] is stored as UTF-16LE, the form that must start 4-byte aligned in rows with string offsets. */
    fun isUtf16(text: String): Boolean = text.any { it.code > 0x7F }

    /** An ISRC (12 ASCII characters) in rekordbox's own form, or the empty string when [isrc] is empty. */
    fun encodeIsrc(isrc: String): ByteArray {
        if (isrc.isEmpty()) return encode("")
        require(isrc.length == ISRC_LENGTH && !isUtf16(isrc)) { "not an ISRC: $isrc" }
        return long(LONG_UTF16, byteArrayOf(3) + isrc.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
    }

    private fun long(kind: Int, content: ByteArray): ByteArray {
        val total = content.size + LONG_HEADER_BYTES
        require(total <= MAX_LONG_BYTES) { "string too long for DeviceSQL: ${content.size} bytes" }
        return littleEndian {
            u8(kind)
            u16(total)
            u8(0)
            bytes(content)
        }
    }
}
