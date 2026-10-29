package io.sonora.core.transcoder.ogg

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class OggStreamWriterTest {

    private val out = ByteArrayOutputStream()
    private val writer = OggStreamWriter(out, serialNumber = 0x01020304)

    private fun packet(size: Int, fill: Int = 0x55) = ByteArray(size) { (fill + it % 7).toByte() }

    private fun pages() = OggPages.parse(out.toByteArray())

    @Test
    fun `the CRC of the check string is the unreflected polynomial 0x04C11DB7 value`() {
        // CRC-32/MPEG-2 style (init 0, no reflection, no final xor) of "123456789".
        assertThat(OggCrc.of("123456789".toByteArray())).isEqualTo(0x89A1897F.toInt())
        assertThat(OggCrc.of(ByteArray(0))).isEqualTo(0)
    }

    @Test
    fun `the CRC matches a page written by libopus through ffmpeg`() {
        // The OpusHead page of an ffmpeg 7 / libopus file, with its CRC field zeroed.
        val head = hex(
            "4f676753" + "00" + "02" + "0000000000000000" + "710a85ed" + "00000000" + "00000000" + "01" + "13" +
                "4f70757348656164" + "01" + "02" + "3801" + "80bb0000" + "0000" + "00",
        )
        assertThat(head).hasLength(47)
        assertThat(OggCrc.of(head)).isEqualTo(0x38C87E0F)
    }

    @Test
    fun `the CRC of a stream can be computed in pieces`() {
        val data = packet(1_000)
        val whole = OggCrc.of(data)
        var crc = 0
        crc = OggCrc.update(crc, data, 0, 300)
        crc = OggCrc.update(crc, data, 300, 700)

        assertThat(crc).isEqualTo(whole)
    }

    @Test
    fun `a first page is laid out as RFC 3533 says`() {
        val body = byteArrayOf(1, 2, 3)
        writer.writePacket(body, granulePosition = 0)
        writer.flushPage()

        val bytes = out.toByteArray()
        assertThat(bytes.size).isEqualTo(27 + 1 + 3)
        assertThat(String(bytes, 0, 4)).isEqualTo("OggS")
        assertThat(bytes[4].toInt()).isEqualTo(0) // version
        assertThat(bytes[5].toInt()).isEqualTo(0x02) // begin of stream
        assertThat(bytes.copyOfRange(6, 14)).isEqualTo(ByteArray(8)) // granule 0
        assertThat(bytes.copyOfRange(14, 18)).isEqualTo(byteArrayOf(4, 3, 2, 1)) // serial, little endian
        assertThat(bytes.copyOfRange(18, 22)).isEqualTo(ByteArray(4)) // sequence 0
        assertThat(bytes[26].toInt()).isEqualTo(1) // one segment
        assertThat(bytes[27].toInt()).isEqualTo(3)
        assertThat(bytes.copyOfRange(28, 31)).isEqualTo(body)
        assertThat(pages().single().crcOk).isTrue()
    }

    @Test
    fun `lacing splits packets in 255 byte segments and ends them with a short one`() {
        for ((size, lacing) in mapOf(
            0 to listOf(0),
            1 to listOf(1),
            254 to listOf(254),
            255 to listOf(255, 0),
            256 to listOf(255, 1),
            510 to listOf(255, 255, 0),
            511 to listOf(255, 255, 1),
        )) {
            val sink = ByteArrayOutputStream()
            val w = OggStreamWriter(sink, 7)
            w.writePacket(packet(size), 100)
            w.flushPage()

            assertThat(OggPages.parse(sink.toByteArray()).single().lacing).isEqualTo(lacing)
        }
    }

    @Test
    fun `several packets share a page and the page carries the granule of the last one`() {
        writer.writePacket(packet(10), 960)
        writer.writePacket(packet(300), 1_920)
        writer.writePacket(packet(20), 2_880)
        writer.flushPage()

        val page = pages().single()
        assertThat(page.lacing).isEqualTo(listOf(10, 255, 45, 20))
        assertThat(page.granulePosition).isEqualTo(2_880)
        assertThat(OggPages.packets(listOf(page)).map { it.size }).isEqualTo(listOf(10, 300, 20))
    }

    @Test
    fun `sequence numbers count up from zero and only the first page begins the stream`() {
        repeat(3) {
            writer.writePacket(packet(50), 960L * (it + 1))
            writer.flushPage()
        }

        val pages = pages()
        assertThat(pages.map { it.sequenceNumber }).isEqualTo(listOf(0, 1, 2))
        assertThat(pages.map { it.beginOfStream }).isEqualTo(listOf(true, false, false))
        assertThat(pages.all { it.serialNumber == 0x01020304 && it.crcOk && !it.continued }).isTrue()
        assertThat(writer.pagesWritten).isEqualTo(3)
    }

    @Test
    fun `a packet longer than a page's 255 segments continues on the next page`() {
        val big = packet(70_000)
        writer.writePacket(big, 4_800)
        writer.finish()

        val pages = pages()
        assertThat(pages).hasSize(2)
        assertThat(pages[0].lacing).hasSize(255)
        assertThat(pages[0].body.size).isEqualTo(255 * 255)
        assertThat(pages[0].granulePosition).isEqualTo(-1L) // no packet ends on this page
        assertThat(pages[0].continued).isFalse()
        assertThat(pages[1].continued).isTrue()
        assertThat(pages[1].granulePosition).isEqualTo(4_800)
        assertThat(OggPages.packets(pages).single()).isEqualTo(big)
    }

    @Test
    fun `a full segment table starts the next page without the continued flag when the packet ended`() {
        repeat(256) { writer.writePacket(packet(10, it), 960L * (it + 1)) }
        writer.finish()

        val pages = pages()
        assertThat(pages).hasSize(2)
        assertThat(pages[0].lacing).hasSize(255)
        assertThat(pages[0].granulePosition).isEqualTo(960L * 255)
        assertThat(pages[1].continued).isFalse()
        assertThat(pages[1].lacing).isEqualTo(listOf(10))
        assertThat(pages[1].granulePosition).isEqualTo(960L * 256)
        assertThat(OggPages.packets(pages)).hasSize(256)
    }

    @Test
    fun `finishing marks the last page as the end of the stream`() {
        writer.writePacket(packet(10), 960)
        writer.flushPage()
        writer.writePacket(packet(10), 1_920)
        writer.finish()

        assertThat(pages().map { it.endOfStream }).isEqualTo(listOf(false, true))
    }

    @Test
    fun `finishing right after a flush writes an empty end page with the last granule`() {
        writer.writePacket(packet(10), 960)
        writer.flushPage()
        writer.finish()

        val last = pages().last()
        assertThat(last.endOfStream).isTrue()
        assertThat(last.lacing).isEmpty()
        assertThat(last.granulePosition).isEqualTo(960)
        assertThat(last.crcOk).isTrue()
    }

    @Test
    fun `finishing can override the granule of the last page`() {
        writer.writePacket(packet(10), 960)
        writer.writePacket(packet(10), 1_920)
        writer.finish(finalGranulePosition = 1_500)

        assertThat(pages().single().granulePosition).isEqualTo(1_500)
    }

    @Test
    fun `nothing can be written after finishing`() {
        writer.writePacket(packet(10), 960)
        writer.finish()

        assertThrows(IllegalStateException::class.java) { writer.writePacket(packet(10), 1_920) }
    }

    @Test
    fun `pending data size reflects the unflushed page`() {
        assertThat(writer.pendingBodyBytes).isEqualTo(0)
        writer.writePacket(packet(300), 960)
        assertThat(writer.pendingBodyBytes).isEqualTo(300)
        writer.flushPage()
        assertThat(writer.pendingBodyBytes).isEqualTo(0)
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
