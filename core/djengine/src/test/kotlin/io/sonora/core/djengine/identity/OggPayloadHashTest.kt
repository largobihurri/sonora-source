package io.sonora.core.djengine.identity

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AudioId
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class OggPayloadHashTest {

    /** Sizes around the lacing limits: multiples of 255, one byte either side, empty, and packets over 255. */
    private val audio = OggBytes.packets(listOf(300, 255, 510, 100, 0, 1, 700, 254, 256, 1000))

    private fun idOf(bytes: ByteArray, maxRead: Int = Int.MAX_VALUE): AudioId =
        AudioPayloadHasher().identify(ArrayByteSource(bytes, maxRead))

    private fun sha256Hex(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expected(packets: List<ByteArray>): String = sha256Hex(ascii("ogg"), *packets.toTypedArray())

    private fun vorbis(
        commentBytes: Int = 40,
        segmentsPerPage: Int = 255,
        packets: List<ByteArray> = audio,
        serial: Long = 0x5EED,
        commentSeed: Int = 61,
        breakAfter: Set<Int> = setOf(0, 2),
    ): ByteArray = OggBytes.stream(
        listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(commentBytes, commentSeed), OggBytes.vorbisSetup()) + packets,
        serial, segmentsPerPage, breakAfter,
    )

    private fun opus(commentBytes: Int = 40, segmentsPerPage: Int = 255, packets: List<ByteArray> = audio): ByteArray =
        OggBytes.stream(
            listOf(OggBytes.opusHead(), OggBytes.opusTags(commentBytes)) + packets,
            segmentsPerPage = segmentsPerPage,
            breakAfter = setOf(0, 1),
        )

    private fun lastSequence(bytes: ByteArray): Long {
        val at = OggBytes.pageOffsets(bytes).last() + 18
        return bytes.u32le(at)
    }

    @Test
    fun `the id is the sha256 of the tag ogg then the packets after the three Vorbis headers`() {
        val id = idOf(vorbis())

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(expected(audio))
    }

    @Test
    fun `Opus skips two header packets and the third packet is audio`() {
        val id = idOf(opus())

        assertThat(id.scheme).isEqualTo("ap1")
        assertThat(id.hash).isEqualTo(expected(audio))
    }

    @Test
    fun `a comment header that grows and spills onto new pages keeps the id`() {
        val small = vorbis(commentBytes = 40)
        val reference = idOf(small)

        for (commentBytes in intArrayOf(0, 300, 5_000, 70_000, 200_000)) {
            val grown = vorbis(commentBytes = commentBytes)

            assertThat(idOf(grown)).isEqualTo(reference)
        }
        val spilled = vorbis(commentBytes = 70_000)
        assertThat(OggBytes.pageOffsets(spilled).size).isGreaterThan(OggBytes.pageOffsets(small).size)
        assertThat(lastSequence(spilled)).isNotEqualTo(lastSequence(small))
    }

    @Test
    fun `an Opus tags packet that grows keeps the id`() {
        val small = opus(commentBytes = 40)
        val big = opus(commentBytes = 100_000)

        assertThat(OggBytes.pageOffsets(big).size).isGreaterThan(OggBytes.pageOffsets(small).size)
        assertThat(idOf(big)).isEqualTo(idOf(small))
        assertThat(idOf(opus(commentBytes = 0))).isEqualTo(idOf(small))
    }

    @Test
    fun `where the pages are cut does not matter`() {
        val reference = idOf(vorbis())

        for (segmentsPerPage in intArrayOf(1, 2, 3, 7, 50, 254)) {
            assertThat(idOf(vorbis(segmentsPerPage = segmentsPerPage))).isEqualTo(reference)
            assertThat(idOf(vorbis(segmentsPerPage = segmentsPerPage, breakAfter = emptySet()))).isEqualTo(reference)
        }
        assertThat(idOf(vorbis(breakAfter = setOf(0, 1, 2, 3, 4, 5, 8)))).isEqualTo(reference)
    }

    @Test
    fun `a header packet that shares its page with audio is still skipped`() {
        val file = OggBytes.stream(listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(50), OggBytes.vorbisSetup()) + audio, breakAfter = setOf(0))

        assertThat(idOf(file).hash).isEqualTo(expected(audio))
    }

    @Test
    fun `sequence numbers granule positions CRCs and the serial number are not hashed`() {
        val file = vorbis()
        val reference = idOf(file)

        assertThat(idOf(vorbis(serial = 0xCAFE))).isEqualTo(reference)
        assertThat(idOf(OggBytes.stream(listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(40), OggBytes.vorbisSetup()) + audio, firstSequence = 4000, breakAfter = setOf(0, 2))))
            .isEqualTo(reference)
        val pages = OggBytes.pageOffsets(file)
        for (page in pages) {
            assertThat(idOf(file.flipped(page + 18))).isEqualTo(reference) // sequence number
            assertThat(idOf(file.flipped(page + 7))).isEqualTo(reference) // granule position
            assertThat(idOf(file.flipped(page + 23))).isEqualTo(reference) // CRC
        }
    }

    @Test
    fun `changing the comment or identification bytes keeps the id and flipping an audio byte changes it`() {
        val file = vorbis()
        val reference = idOf(file)

        assertThat(idOf(vorbis(commentSeed = 99))).isEqualTo(reference)
        assertThat(idOf(file.flipped(file.size - 1))).isNotEqualTo(reference)
        assertThat(idOf(file.flipped(file.size - 1500))).isNotEqualTo(reference)
        assertThat(idOf(file.flipped(file.size / 2 + 300))).isNotEqualTo(reference)
        // an audio packet first byte right after the setup header
        val firstAudioPage = OggBytes.pageOffsets(file)[2]
        val firstAudioByte = firstAudioPage + 27 + (file[firstAudioPage + 26].toInt() and 0xFF)
        assertThat(idOf(file.flipped(firstAudioByte))).isNotEqualTo(reference)
    }

    @Test
    fun `flipping a header packet byte keeps the id`() {
        val file = vorbis()
        val reference = idOf(file)
        val pages = OggBytes.pageOffsets(file)

        // the identification page, then the page that holds the comment and setup headers
        for (page in pages.take(2)) {
            val body = page + 27 + (file[page + 26].toInt() and 0xFF)
            assertThat(idOf(file.flipped(body + 20))).isEqualTo(reference)
        }
    }

    @Test
    fun `Vorbis and Opus streams of the same bytes are told apart by their header counts`() {
        val packets = OggBytes.packets(listOf(200, 300, 400, 500, 600), seed = 300)
        val asVorbis = OggBytes.stream(listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(10), OggBytes.vorbisSetup()) + packets)
        val asOpus = OggBytes.stream(listOf(OggBytes.opusHead(), OggBytes.opusTags(10)) + packets)

        assertThat(idOf(asVorbis).hash).isEqualTo(expected(packets))
        assertThat(idOf(asOpus).hash).isEqualTo(expected(packets))
    }

    @Test
    fun `FLAC in Ogg skips the mapping header and the announced metadata packets`() {
        fun flac(commentBytes: Int, headerPackets: Int = 3, segmentsPerPage: Int = 255) = OggBytes.stream(
            listOf(
                OggBytes.flacId(headerPackets),
                OggBytes.flacBlock(FlacBytes.VORBIS_COMMENT, commentBytes),
                OggBytes.flacBlock(FlacBytes.PADDING, 300),
                OggBytes.flacBlock(FlacBytes.PICTURE, 40, last = true),
            ) + audio,
            segmentsPerPage = segmentsPerPage,
            breakAfter = setOf(0),
        )

        val reference = idOf(flac(100))

        assertThat(reference.scheme).isEqualTo("ap1")
        assertThat(reference.hash).isEqualTo(expected(audio))
        assertThat(idOf(flac(90_000))).isEqualTo(reference)
        assertThat(idOf(flac(100, segmentsPerPage = 5))).isEqualTo(reference)
    }

    @Test
    fun `FLAC in Ogg with an unknown header count is resolved only by the last metadata block flag`() {
        val onlyStreamInfo = OggBytes.stream(listOf(OggBytes.flacId(0, lastMetadataBlock = true)) + audio, breakAfter = setOf(0))
        val unknown = OggBytes.stream(listOf(OggBytes.flacId(0, lastMetadataBlock = false), OggBytes.flacBlock(FlacBytes.PADDING, 20, last = true)) + audio, breakAfter = setOf(0))
        val one = OggBytes.stream(listOf(OggBytes.flacId(1), OggBytes.flacBlock(FlacBytes.PADDING, 20, last = true)) + audio, breakAfter = setOf(0))

        assertThat(idOf(onlyStreamInfo).hash).isEqualTo(expected(audio))
        assertThat(idOf(one).hash).isEqualTo(expected(audio))
        assertThat(idOf(unknown).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a stream cut inside a page or ending in the middle of a packet falls back to the whole file`() {
        val file = vorbis()

        assertThat(idOf(file.copyOf(file.size - 1)).scheme).isEqualTo("wf1")
        assertThat(idOf(file.copyOf(file.size - 700)).scheme).isEqualTo("wf1")
        // one-segment pages, last page dropped: the previous page ends with a 255 lacing value
        val packets = listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(10), OggBytes.vorbisSetup(), filler(600, 1))
        val oneSegment = OggBytes.stream(packets, segmentsPerPage = 1)
        val lastPage = OggBytes.pageOffsets(oneSegment).last()
        val lastLace = oneSegment[lastPage + 27].toInt() and 0xFF
        assertThat(lastLace).isLessThan(255)
        val pending = oneSegment.copyOf(lastPage)
        assertThat(pending[OggBytes.pageOffsets(pending).last() + 27].toInt() and 0xFF).isEqualTo(255)
        assertThat(idOf(pending).scheme).isEqualTo("wf1")
    }

    @Test
    fun `lacing that contradicts the continuation flags falls back to the whole file`() {
        val file = vorbis(segmentsPerPage = 3)
        val pages = OggBytes.pageOffsets(file)
        val continued = pages.filter { file[it + 5].toInt() and OggBytes.CONTINUED != 0 }
        val fresh = pages.drop(1).filter { file[it + 5].toInt() and OggBytes.CONTINUED == 0 }
        assertThat(continued).isNotEmpty()
        assertThat(fresh).isNotEmpty()

        // a page claims to continue a packet that ended on the previous page
        val spurious = file.copyOf().also { it[fresh.last() + 5] = (it[fresh.last() + 5].toInt() or OggBytes.CONTINUED).toByte() }
        // a page that follows an unfinished packet does not say so
        val missing = file.copyOf().also { it[continued.first() + 5] = (it[continued.first() + 5].toInt() and OggBytes.CONTINUED.inv()).toByte() }
        // the first page claims to be a continuation
        val firstContinued = file.copyOf().also { it[5] = (it[5].toInt() or OggBytes.CONTINUED).toByte() }

        assertThat(idOf(file).scheme).isEqualTo("ap1")
        assertThat(idOf(spurious).scheme).isEqualTo("wf1")
        assertThat(idOf(missing).scheme).isEqualTo("wf1")
        assertThat(idOf(firstContinued).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a segment table that claims more data than the file holds falls back to the whole file`() {
        val file = vorbis()
        val lastPage = OggBytes.pageOffsets(file).last()
        val lastLace = lastPage + 27 + (file[lastPage + 26].toInt() and 0xFF) - 1
        assertThat(file[lastLace].toInt() and 0xFF).isLessThan(255)
        val inflated = file.copyOf().also { it[lastLace] = 255.toByte() }

        assertThat(idOf(inflated).scheme).isEqualTo("wf1")
        val more = file.copyOf().also { it[lastPage + 26] = 255.toByte() }
        assertThat(idOf(more).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a broken capture pattern or a stream structure version other than zero falls back to the whole file`() {
        val file = vorbis(segmentsPerPage = 20)
        val second = OggBytes.pageOffsets(file)[1]

        assertThat(idOf(file.copyOf().also { it[second] = 'X'.code.toByte() }).scheme).isEqualTo("wf1")
        assertThat(idOf(file.copyOf().also { it[second + 4] = 1 }).scheme).isEqualTo("wf1")
        assertThat(idOf(joinBytes(file, filler(10, 7))).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a first page that is not marked as the start of the stream falls back to the whole file`() {
        val file = vorbis()

        assertThat(idOf(file.copyOf().also { it[5] = (it[5].toInt() and OggBytes.FIRST.inv()).toByte() }).scheme).isEqualTo("wf1")
    }

    @Test
    fun `a stream that ends before all its header packets or without audio falls back to the whole file`() {
        val headersOnly = OggBytes.stream(listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(40), OggBytes.vorbisSetup()))
        val missingSetup = OggBytes.stream(listOf(OggBytes.vorbisId(), OggBytes.vorbisComment(40)))
        val opusHeadersOnly = OggBytes.stream(listOf(OggBytes.opusHead(), OggBytes.opusTags(40)))

        assertThat(idOf(headersOnly).scheme).isEqualTo("wf1")
        assertThat(idOf(missingSetup).scheme).isEqualTo("wf1")
        assertThat(idOf(opusHeadersOnly).scheme).isEqualTo("wf1")
    }

    @Test
    fun `chained and multiplexed streams fall back to the whole file`() {
        val one = vorbis(serial = 1, segmentsPerPage = 8)
        val two = vorbis(serial = 2, packets = OggBytes.packets(listOf(400, 500), seed = 400))
        val chained = joinBytes(one, two)
        val pages = OggBytes.pageOffsets(one)
        val interleaved = one.copyOf().also { le32(2).copyInto(it, pages[4] + 14) }

        assertThat(idOf(one).scheme).isEqualTo("ap1")
        assertThat(idOf(chained).scheme).isEqualTo("wf1")
        assertThat(idOf(interleaved).scheme).isEqualTo("wf1")
        // the whole-file id changes with any byte, tags included
        assertThat(idOf(chained)).isNotEqualTo(idOf(joinBytes(vorbis(serial = 1, segmentsPerPage = 8, commentSeed = 8), two)))
    }

    @Test
    fun `a codec the walker does not know falls back to the whole file`() {
        val speex = OggBytes.stream(listOf(ascii("Speex   ") + filler(72, 1), filler(30, 2)) + audio, breakAfter = setOf(0, 1))
        val theora = OggBytes.stream(listOf(byteArrayOf(0x80.toByte()) + ascii("theora") + filler(30, 3)) + audio, breakAfter = setOf(0))
        val noise = OggBytes.stream(listOf(filler(40, 4)) + audio, breakAfter = setOf(0))
        val shortFirstPacket = OggBytes.stream(listOf(byteArrayOf(1)) + audio, breakAfter = setOf(0))

        for (file in listOf(speex, theora, noise, shortFirstPacket)) assertThat(idOf(file).scheme).isEqualTo("wf1")
    }

    @Test
    fun `an OggS marker alone or a lone page is not a stream`() {
        assertThat(idOf(ascii("OggS")).scheme).isEqualTo("wf1")
        assertThat(idOf(ascii("OggS") + ByteArray(30)).scheme).isEqualTo("wf1")
        assertThat(idOf(OggBytes.stream(listOf(OggBytes.vorbisId()))).scheme).isEqualTo("wf1")
    }

    @Test
    fun `the id does not depend on how the source splits its reads`() {
        val file = vorbis(commentBytes = 3000, segmentsPerPage = 60, packets = OggBytes.packets(List(60) { 1000 + it * 37 }, seed = 500))
        val expected = idOf(file)

        assertThat(expected.scheme).isEqualTo("ap1")
        for (maxRead in intArrayOf(1, 5, 27, 282, 4096, 65_535)) {
            assertThat(idOf(file, maxRead)).isEqualTo(expected)
        }
    }

    @Test
    fun `an io error inside a page header or the payload propagates`() {
        val file = vorbis(segmentsPerPage = 40, packets = OggBytes.packets(List(50) { 900 }, seed = 600))
        val pages = OggBytes.pageOffsets(file)

        assertThrows(IOException::class.java) { AudioPayloadHasher().identify(ArrayByteSource(file, failAt = pages[pages.size / 2] + 10L)) }
        assertThrows(IOException::class.java) { AudioPayloadHasher().identify(ArrayByteSource(file, failAt = file.size - 5L)) }
    }
}
