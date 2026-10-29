package io.sonora.core.transcoder.ogg

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One Ogg page as a reader sees it (RFC 3533 section 6). */
class OggPage(
    val headerType: Int,
    val granulePosition: Long,
    val serialNumber: Int,
    val sequenceNumber: Int,
    val storedCrc: Int,
    val computedCrc: Int,
    val lacing: List<Int>,
    val body: ByteArray,
) {
    val crcOk: Boolean get() = storedCrc == computedCrc
    val continued: Boolean get() = headerType and 0x01 != 0
    val beginOfStream: Boolean get() = headerType and 0x02 != 0
    val endOfStream: Boolean get() = headerType and 0x04 != 0

    /** The packets that end on this page (the first one may have started on an earlier page): sizes only. */
    val completedPacketSizes: List<Int>
        get() {
            val sizes = ArrayList<Int>()
            var current = 0
            for (value in lacing) {
                current += value
                if (value < 255) {
                    sizes += current
                    current = 0
                }
            }
            return sizes
        }
}

/**
 * A test-side Ogg reader: splits a byte stream into pages, recomputing each CRC independently of [OggCrc] (a
 * bitwise implementation), and rebuilds the packets. Fails on any framing error.
 */
object OggPages {

    fun parse(bytes: ByteArray): List<OggPage> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pages = ArrayList<OggPage>()
        var at = 0
        while (at < bytes.size) {
            check(bytes.size - at >= 27) { "Truncated page header at $at" }
            check(String(bytes, at, 4, Charsets.ISO_8859_1) == "OggS") { "No capture pattern at $at" }
            check(bytes[at + 4].toInt() == 0) { "Unsupported stream structure version" }
            val segmentCount = bytes[at + 26].toInt() and 0xFF
            val lacing = List(segmentCount) { bytes[at + 27 + it].toInt() and 0xFF }
            val headerSize = 27 + segmentCount
            val bodySize = lacing.sum()
            check(at + headerSize + bodySize <= bytes.size) { "Truncated page body at $at" }
            val stored = buffer.getInt(at + 22)
            val copy = bytes.copyOfRange(at, at + headerSize + bodySize)
            for (i in 22 until 26) copy[i] = 0
            pages += OggPage(
                headerType = bytes[at + 5].toInt() and 0xFF,
                granulePosition = buffer.getLong(at + 6),
                serialNumber = buffer.getInt(at + 14),
                sequenceNumber = buffer.getInt(at + 18),
                storedCrc = stored,
                computedCrc = bitwiseCrc(copy),
                lacing = lacing,
                body = bytes.copyOfRange(at + headerSize, at + headerSize + bodySize),
            )
            at += headerSize + bodySize
        }
        return pages
    }

    /** The complete packets of [pages], in order; fails when a packet is left unfinished. */
    fun packets(pages: List<OggPage>): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        for (page in pages) {
            var offset = 0
            for (value in page.lacing) {
                current.write(page.body, offset, value)
                offset += value
                if (value < 255) {
                    packets += current.toByteArray()
                    current = java.io.ByteArrayOutputStream()
                }
            }
        }
        check(current.size() == 0) { "The stream ends inside a packet" }
        return packets
    }

    /** CRC-32 with polynomial 0x04C11DB7, zero initial value, no reflection, no final xor (RFC 3533 section 6). */
    fun bitwiseCrc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }
}
