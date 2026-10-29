package io.sonora.core.transcoder.ogg

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes one logical Ogg bitstream to [out] as pages (RFC 3533). Packets are queued with the granule position the
 * stream's codec assigns to the point where each one ends; a page is written when its 255-entry segment table is full
 * or when [flushPage] / [finish] asks for it. A packet that does not fit the table continues on the next page (header
 * flag "continued packet"), and a page on which no packet ends carries the granule position -1.
 *
 * Packets are laced as runs of 255-byte segments closed by a shorter one (a 255-multiple packet ends with a
 * zero-length segment). The first page carries the "beginning of stream" flag and the page written by [finish] the
 * "end of stream" flag. Not thread-safe.
 */
class OggStreamWriter(private val out: OutputStream, private val serialNumber: Int) {

    private val lacing = ByteArray(MAX_SEGMENTS)
    private val body = java.io.ByteArrayOutputStream(BODY_CAPACITY)
    private var segments = 0
    private var pageGranule = NO_GRANULE
    private var lastGranule = 0L
    private var continued = false
    private var sequence = 0
    private var finished = false

    /** Pages written so far. */
    val pagesWritten: Int get() = sequence

    /** Bytes of packet data queued for the page being built. */
    val pendingBodyBytes: Int get() = body.size()

    /**
     * Queues [packet], which ends at [granulePosition] (the stream's own unit; for Opus the 48 kHz samples decoded
     * up to the end of the packet, pre-skip included).
     */
    fun writePacket(packet: ByteArray, granulePosition: Long) {
        check(!finished) { "The stream is finished" }
        var offset = 0
        var remaining = packet.size
        while (true) {
            if (segments == MAX_SEGMENTS) writePage(endOfStream = false)
            val size = minOf(remaining, SEGMENT)
            lacing[segments++] = size.toByte()
            body.write(packet, offset, size)
            offset += size
            remaining -= size
            if (size < SEGMENT) break
            // A final full segment still needs its closing zero-length segment, which may start the next page.
            if (remaining == 0) {
                if (segments == MAX_SEGMENTS) writePage(endOfStream = false)
                lacing[segments++] = 0
                break
            }
        }
        pageGranule = granulePosition
        lastGranule = granulePosition
        if (segments == MAX_SEGMENTS) writePage(endOfStream = false)
    }

    /** Writes the queued packets as a page now; nothing happens when none are queued. */
    fun flushPage() {
        check(!finished) { "The stream is finished" }
        if (segments > 0) writePage(endOfStream = false)
    }

    /**
     * Writes what is queued as the last page, flagged end of stream, and flushes [out]. A [finalGranulePosition] replaces the
     * page's granule (Opus uses it to trim the end); with nothing queued the page is empty and carries the last
     * granule written. The stream is not closed.
     */
    fun finish(finalGranulePosition: Long? = null) {
        check(!finished) { "The stream is finished" }
        if (segments == 0) pageGranule = lastGranule
        if (finalGranulePosition != null) pageGranule = finalGranulePosition
        writePage(endOfStream = true)
        finished = true
        out.flush()
    }

    private fun writePage(endOfStream: Boolean) {
        val data = body.toByteArray()
        val header = ByteBuffer.allocate(HEADER_SIZE + segments).order(ByteOrder.LITTLE_ENDIAN)
        header.put(CAPTURE).put(0)
        var type = 0
        if (continued) type = type or CONTINUED
        if (sequence == 0) type = type or BEGIN_OF_STREAM
        if (endOfStream) type = type or END_OF_STREAM
        header.put(type.toByte())
        header.putLong(pageGranule)
        header.putInt(serialNumber)
        header.putInt(sequence)
        header.putInt(0) // CRC placeholder
        header.put(segments.toByte())
        header.put(lacing, 0, segments)
        val head = header.array()
        var crc = OggCrc.update(0, head, 0, head.size)
        crc = OggCrc.update(crc, data, 0, data.size)
        ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).putInt(CRC_OFFSET, crc)
        out.write(head)
        out.write(data)
        // The next page continues a packet when the last segment written was a full one.
        continued = segments > 0 && lacing[segments - 1].toInt() and 0xFF == SEGMENT
        sequence++
        segments = 0
        body.reset()
        pageGranule = NO_GRANULE
    }

    private companion object {
        val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)
        const val SEGMENT = 255
        const val MAX_SEGMENTS = 255
        const val HEADER_SIZE = 27
        const val CRC_OFFSET = 22
        const val BODY_CAPACITY = 8 * 1024
        const val CONTINUED = 0x01
        const val BEGIN_OF_STREAM = 0x02
        const val END_OF_STREAM = 0x04
        const val NO_GRANULE = -1L
    }
}
