package io.sonora.core.rekordbox.anlz

import java.io.DataOutputStream

/**
 * Serialises a list of [AnlzSection]s into an ANLZ file: the `PMAI` file header followed by the sections in the given
 * order. Everything is big-endian.
 */
object AnlzEncoder {
    private const val FILE_HEADER_BYTES = 28
    private const val SECTION_HEADER_BYTES = 12

    fun encode(sections: List<AnlzSection>): ByteArray {
        val encoded = sections.map { section ->
            val header = section.header()
            val body = section.body()
            bigEndian {
                writeTag(section.fourCc)
                writeInt(SECTION_HEADER_BYTES + header.size)
                writeInt(SECTION_HEADER_BYTES + header.size + body.size)
                write(header)
                write(body)
            }
        }
        val fileLength = FILE_HEADER_BYTES + encoded.sumOf { it.size }
        return bigEndian {
            writeTag("PMAI")
            writeInt(FILE_HEADER_BYTES)
            writeInt(fileLength)
            // Constants rekordbox writes in every PMAI header; meaning unknown.
            writeInt(0x0000_0001)
            writeInt(0x0001_0000)
            writeInt(0x0001_0000)
            writeInt(0)
            encoded.forEach(::write)
        }
    }

    private fun DataOutputStream.writeTag(fourCc: String) {
        require(fourCc.length == 4 && fourCc.all { it.code in 0x20..0x7E }) { "bad section tag: $fourCc" }
        writeBytes(fourCc)
    }
}
