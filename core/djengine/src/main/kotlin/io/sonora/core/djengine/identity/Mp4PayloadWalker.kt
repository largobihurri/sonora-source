package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * ISO base media files: M4A with AAC or ALAC, and any other MP4 whose first box is `ftyp`. The payload is the body of
 * every top-level `mdat` box, in file order, so the sample data of a fragmented file counts as a whole and box
 * boundaries between `mdat` boxes do not matter. Everything else is excluded: `ftyp`, `moov` (its `stco`/`co64` chunk
 * offsets change whenever a tag edit moves the sample data, and it holds `udta/meta/ilst` too), `moof`, `free`,
 * `skip`, `uuid` and any top-level `meta`. Moving `moov` before or after `mdat` therefore keeps the id.
 *
 * The top-level boxes must tile the file exactly. A 32-bit size of 1 means a 64-bit `largesize` follows the type, a
 * size of 0 means the box runs to the end of the file, and a size below its header, a box that ends beyond the file,
 * a `largesize` of 2^63 or more, or fewer than eight bytes left after the last box is a malformed tree (`wf1`), as
 * is a file without any non-empty `mdat`. Box layout follows ISO/IEC 14496-12 section 4.2.
 */
object Mp4PayloadWalker : PayloadWalker {

    override val containerTag: String = "mp4"

    private const val HEADER_SIZE = 8
    private const val LARGE_HEADER_SIZE = 16
    private const val TO_END_OF_FILE = 0L
    private const val LARGE_SIZE = 1L

    override fun matches(prefix: ByteArray): Boolean = prefix.size >= HEADER_SIZE && prefix.hasAscii(4, "ftyp")

    override fun payloadRanges(source: ByteSource): List<LongRange>? {
        val length = source.length
        val header = ByteArray(LARGE_HEADER_SIZE)
        val ranges = ArrayList<LongRange>()
        var position = 0L
        while (position < length) {
            val left = length - position
            if (left < HEADER_SIZE || !source.readFully(position, header, HEADER_SIZE)) return null
            var headerSize = HEADER_SIZE.toLong()
            var size = header.u32be(0)
            when (size) {
                TO_END_OF_FILE -> size = left
                LARGE_SIZE -> {
                    if (left < LARGE_HEADER_SIZE || !source.readFully(position, header, LARGE_HEADER_SIZE)) return null
                    headerSize = LARGE_HEADER_SIZE.toLong()
                    size = header.u64be(HEADER_SIZE)
                }
            }
            if (size < headerSize || size > left) return null
            if (header.hasAscii(4, "mdat") && size > headerSize) ranges += (position + headerSize) until (position + size)
            position += size
        }
        return ranges.ifEmpty { null }
    }
}
