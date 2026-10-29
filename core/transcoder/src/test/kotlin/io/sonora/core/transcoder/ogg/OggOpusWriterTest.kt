package io.sonora.core.transcoder.ogg

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class OggOpusWriterTest {

    private val out = ByteArrayOutputStream()
    private val tags = OpusHeaders.tags("Sonora", listOf(VorbisComment("TITLE", "Aria")))

    /** A valid 20 ms CELT packet (TOC 0xFC) of [size] bytes. */
    private fun packet(size: Int = 480, fill: Int = 1) = ByteArray(size) { if (it == 0) 0xFC.toByte() else (fill + it).toByte() }

    private fun writer(preSkip: Int = 312, tagBytes: ByteArray = tags, maxPageSamples: Int = 48_000) =
        OggOpusWriter(out, serialNumber = 42, channels = 2, preSkip = preSkip, inputSampleRateHz = 44_100, tags = tagBytes, maxPageSamples = maxPageSamples)

    private fun pages() = OggPages.parse(out.toByteArray())

    @Test
    fun `the first page is the OpusHead alone and the second the OpusTags`() {
        val w = writer()
        w.writePacket(packet())
        w.finish(realSamples = 960)

        val pages = pages()
        assertThat(pages[0].beginOfStream).isTrue()
        assertThat(pages[0].sequenceNumber).isEqualTo(0)
        assertThat(pages[0].granulePosition).isEqualTo(0)
        assertThat(pages[0].body).isEqualTo(OpusHeaders.head(2, 312, 44_100))
        assertThat(pages[1].beginOfStream).isFalse()
        assertThat(pages[1].sequenceNumber).isEqualTo(1)
        assertThat(pages[1].granulePosition).isEqualTo(0)
        assertThat(pages[1].body).isEqualTo(tags)
        assertThat(pages.all { it.crcOk && it.serialNumber == 42 }).isTrue()
    }

    @Test
    fun `audio starts on a page of its own after the headers`() {
        val w = writer()
        w.writePacket(packet())
        w.finish(realSamples = 960)

        val audio = pages()[2]
        assertThat(audio.continued).isFalse()
        assertThat(audio.sequenceNumber).isEqualTo(2)
    }

    @Test
    fun `OpusTags longer than a page spreads over several pages and audio still starts afresh`() {
        val big = OpusHeaders.tags("Sonora", listOf(VorbisComment("METADATA_BLOCK_PICTURE", "A".repeat(70_000))))
        val w = writer(tagBytes = big)
        w.writePacket(packet())
        w.finish(realSamples = 960)

        val pages = pages()
        assertThat(pages).hasSize(4)
        assertThat(pages[1].granulePosition).isEqualTo(-1) // the tags packet does not end here
        assertThat(pages[2].continued).isTrue()
        assertThat(pages[2].granulePosition).isEqualTo(0)
        assertThat(pages[3].continued).isFalse()
        assertThat(OggPages.packets(pages.subList(0, 3)).last()).isEqualTo(big)
    }

    @Test
    fun `granule positions count samples at 48 kHz from the start including the pre-skip`() {
        val w = writer(maxPageSamples = 960 * 2)
        repeat(6) { w.writePacket(packet(fill = it)) }
        w.finish(realSamples = 6 * 960 - 312)

        val audio = pages().drop(2)
        assertThat(audio.map { it.granulePosition }).isEqualTo(listOf(1_920L, 3_840L, 5_760L))
    }

    @Test
    fun `the last granule is the pre-skip plus the real samples so decoders trim the end exactly`() {
        val w = writer()
        repeat(5) { w.writePacket(packet(fill = it)) }
        w.finish(realSamples = 4_000)

        val last = pages().last()
        assertThat(last.endOfStream).isTrue()
        assertThat(last.granulePosition).isEqualTo(312L + 4_000)
    }

    @Test
    fun `the last granule never claims more samples than the packets hold`() {
        val w = writer()
        repeat(5) { w.writePacket(packet(fill = it)) }
        w.finish(realSamples = 5_000)

        assertThat(pages().last().granulePosition).isEqualTo(5L * 960)
    }

    @Test
    fun `a stream without audio is the headers and an empty end page at granule zero`() {
        val w = writer()
        w.finish(realSamples = 0)

        val pages = pages()
        assertThat(pages).hasSize(3)
        assertThat(pages[2].endOfStream).isTrue()
        assertThat(pages[2].lacing).isEmpty()
        assertThat(pages[2].granulePosition).isEqualTo(0)
    }

    @Test
    fun `pages are cut after about a second of audio`() {
        val w = writer()
        repeat(120) { w.writePacket(packet(size = 100, fill = it)) }
        w.finish(realSamples = 120L * 960 - 312)

        val audio = pages().drop(2)
        assertThat(audio.map { it.completedPacketSizes.size }).isEqualTo(listOf(50, 50, 20))
        assertThat(OggPages.packets(pages()).drop(2)).hasSize(120)
    }

    @Test
    fun `the packets read back are the packets written`() {
        val w = writer()
        val sent = List(30) { packet(size = 50 + it * 17, fill = it) }
        sent.forEach(w::writePacket)
        w.finish(realSamples = 30L * 960 - 312)

        // Truth compares the arrays of a list by reference, so compare their contents.
        assertThat(OggPages.packets(pages()).drop(2).map { it.toList() }).isEqualTo(sent.map { it.toList() })
    }

    @Test
    fun `the number of samples written is the sum of the packet durations`() {
        val w = writer()
        w.writePacket(packet())
        w.writePacket(byteArrayOf(0xF0.toByte(), 1, 2)) // 10 ms
        assertThat(w.packetSamples).isEqualTo(960L + 480)
    }

    @Test
    fun `an empty or malformed packet is refused`() {
        val w = writer()
        assertThrows(IllegalArgumentException::class.java) { w.writePacket(ByteArray(0)) }
    }

    @Test
    fun `nothing can be written after finishing`() {
        val w = writer()
        w.finish(realSamples = 0)
        assertThrows(IllegalStateException::class.java) { w.writePacket(packet()) }
    }
}
