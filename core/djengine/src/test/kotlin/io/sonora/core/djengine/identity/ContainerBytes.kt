package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource
import java.io.IOException
import java.util.Random

/** A [ByteSource] over an array. [maxRead] caps every read, to prove that nothing depends on read sizes. */
internal class ArrayByteSource(
    private val bytes: ByteArray,
    private val maxRead: Int = Int.MAX_VALUE,
    private val claimedLength: Long = bytes.size.toLong(),
    private val failAt: Long = -1,
) : ByteSource {

    var readCalls = 0
        private set
    var bytesRead = 0L
        private set

    override val length: Long get() = claimedLength

    override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int {
        require(position >= 0) { "negative position" }
        readCalls++
        if (failAt in position until position + count) throw IOException("injected failure at $failAt")
        if (position >= bytes.size) return -1
        val n = minOf(count, maxRead, (bytes.size - position).toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        bytesRead += n
        return n
    }

    override fun close() = Unit
}

internal fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

internal fun joinBytes(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (part in parts) {
        System.arraycopy(part, 0, out, at, part.size)
        at += part.size
    }
    return out
}

internal fun be16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

internal fun be32(value: Long) =
    byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

internal fun le32(value: Long) =
    byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

internal fun le64(value: Long) = le32(value) + le32(value ushr 32)

/** [count] deterministic bytes in 0..0xFD: no 0xFF, so they never contain an MPEG or FLAC sync word. */
internal fun filler(count: Int, seed: Int): ByteArray {
    val random = Random(seed.toLong())
    return ByteArray(count) { random.nextInt(0xFE).toByte() }
}

internal fun ByteArray.flipped(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

/** Size of [this] as four syncsafe bytes (7 bits each). */
internal fun syncsafe(value: Int) =
    byteArrayOf((value shr 21 and 0x7F).toByte(), (value shr 14 and 0x7F).toByte(), (value shr 7 and 0x7F).toByte(), (value and 0x7F).toByte())

/** Tag blocks shared by the MP3 and FLAC builders. */
internal object TagBytes {

    /** An ID3v2.4 tag with a [bodySize]-byte body; [footer] adds the `3DI` footer and its flag. */
    fun id3v2(bodySize: Int, seed: Int = 1, footer: Boolean = false): ByteArray {
        val header = joinBytes(ascii("ID3"), byteArrayOf(4, 0, if (footer) 0x10 else 0), syncsafe(bodySize))
        val body = filler(bodySize, seed)
        val end = if (footer) joinBytes(ascii("3DI"), byteArrayOf(4, 0, 0x10), syncsafe(bodySize)) else ByteArray(0)
        return joinBytes(header, body, end)
    }

    fun id3v1(seed: Int = 2): ByteArray = joinBytes(ascii("TAG"), filler(125, seed))

    fun tagPlus(seed: Int = 3): ByteArray = joinBytes(ascii("TAG+"), filler(223, seed))

    /** An APEv2 tag whose items are [itemBytes] long; [header] adds the 32-byte header and the flag in the footer. */
    fun ape(itemBytes: Int, seed: Int = 4, header: Boolean = false): ByteArray {
        val size = (itemBytes + 32).toLong()
        fun block(flags: Long) = joinBytes(ascii("APETAGEX"), le32(2000), le32(size), le32(1), le32(flags), ByteArray(8))
        val items = filler(itemBytes, seed)
        return if (header) joinBytes(block(0xA0000000L), items, block(0x80000000L)) else joinBytes(items, block(0))
    }

    /** A Lyrics3v2 block holding [textBytes] of lyrics. */
    fun lyrics3(textBytes: Int, seed: Int = 5): ByteArray {
        val content = joinBytes(ascii("LYRICSBEGIN"), ascii("LYR0${textBytes.toString().padStart(5, '0')}"), filler(textBytes, seed))
        return joinBytes(content, ascii(content.size.toString().padStart(6, '0')), ascii("LYRICS200"))
    }
}

/** MPEG audio frames whose lengths are written out in the specs, so the tests do not repeat the walker's formula. */
internal object Mp3Bytes {

    class Spec(
        val versionBits: Int,
        val layerBits: Int,
        val bitrateIndex: Int,
        val sampleRateIndex: Int,
        val length: Int,
        val padding: Int = 0,
    )

    /** MPEG-1 layer III, 128 kbit/s, 44.1 kHz: 417 bytes, 418 with the padding bit. */
    val MPEG1_L3 = Spec(3, 1, 9, 0, 417)
    val MPEG1_L3_PADDED = Spec(3, 1, 9, 0, 418, padding = 1)

    /** MPEG-1 layer I, 128 kbit/s, 44.1 kHz: (34 slots) * 4 bytes. */
    val MPEG1_L1 = Spec(3, 3, 4, 0, 136)

    /** MPEG-1 layer II, 160 kbit/s, 48 kHz. */
    val MPEG1_L2 = Spec(3, 2, 9, 1, 480)

    /** MPEG-2 layer III, 64 kbit/s, 22.05 kHz. */
    val MPEG2_L3 = Spec(2, 1, 8, 0, 208)

    /** MPEG-2.5 layer III, 32 kbit/s, 8 kHz. */
    val MPEG25_L3 = Spec(0, 1, 4, 2, 288)

    fun frame(spec: Spec, seed: Int, marker: String? = null): ByteArray {
        val header = byteArrayOf(
            0xFF.toByte(),
            (0xE0 or (spec.versionBits shl 3) or (spec.layerBits shl 1) or 1).toByte(),
            ((spec.bitrateIndex shl 4) or (spec.sampleRateIndex shl 2) or (spec.padding shl 1)).toByte(),
            0,
        )
        val body = filler(spec.length - 4, seed)
        if (marker != null) ascii(marker).copyInto(body, 32)
        return header + body
    }

    /** [count] MPEG-1 layer III frames with the 417/418 padding pattern of a 44.1 kHz stream. */
    fun stream(count: Int, seed: Int = 10): ByteArray =
        joinBytes(*Array(count) { frame(if (it % 3 == 2) MPEG1_L3_PADDED else MPEG1_L3, seed + it) })

    fun streamOf(spec: Spec, count: Int, seed: Int = 10): ByteArray =
        joinBytes(*Array(count) { frame(spec, seed + it) })
}

internal object FlacBytes {

    const val STREAMINFO = 0
    const val PADDING = 1
    const val VORBIS_COMMENT = 4
    const val PICTURE = 6

    fun block(type: Int, body: ByteArray, last: Boolean = false): ByteArray =
        joinBytes(
            byteArrayOf((type or if (last) 0x80 else 0).toByte()),
            byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()),
            body,
        )

    fun streamInfo(seed: Int = 20): ByteArray = filler(34, seed)

    /** `fLaC` and the metadata [blocks] (type to body); the last one gets the last-block flag. */
    fun header(blocks: List<Pair<Int, ByteArray>>): ByteArray =
        joinBytes(ascii("fLaC"), *Array(blocks.size) { block(blocks[it].first, blocks[it].second, it == blocks.lastIndex) })

    /** [count] frames: sync code 0xFFF8 and filler without 0xFF. */
    fun frames(count: Int, frameBytes: Int = 600, seed: Int = 30): ByteArray =
        joinBytes(*Array(count) { byteArrayOf(0xFF.toByte(), 0xF8.toByte()) + filler(frameBytes - 2, seed + it) })
}

internal object WavBytes {

    fun chunk(id: String, body: ByteArray, declaredSize: Long = body.size.toLong()): ByteArray =
        joinBytes(ascii(id), le32(declaredSize), body, if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    fun fmt(seed: Int = 40): ByteArray = chunk("fmt ", filler(16, seed))

    fun data(sound: ByteArray, declaredSize: Long = sound.size.toLong()): ByteArray = chunk("data", sound, declaredSize)

    fun riff(vararg chunks: ByteArray): ByteArray {
        val body = joinBytes(*chunks)
        return joinBytes(ascii("RIFF"), le32(4L + body.size), ascii("WAVE"), body)
    }

    /** RF64: a `ds64` chunk with [dataSize], then [chunks]; callers write 0xFFFFFFFF sizes where the format does. */
    fun rf64(dataSize: Long, vararg chunks: ByteArray): ByteArray {
        val ds64 = chunk("ds64", joinBytes(le64(0), le64(dataSize), le64(0), le32(0)))
        return joinBytes(ascii("RF64"), le32(0xFFFFFFFFL), ascii("WAVE"), ds64, *chunks)
    }
}

internal object AiffBytes {

    fun chunk(id: String, body: ByteArray, declaredSize: Long = body.size.toLong()): ByteArray =
        joinBytes(ascii(id), be32(declaredSize), body, if (body.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    /** COMM of a stereo 16-bit 44.1 kHz file: [frames] frames and the 80-bit extended sample rate. */
    fun comm(frames: Long = 1000): ByteArray {
        val rate = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        return chunk("COMM", joinBytes(be16(2), be32(frames), be16(16), rate))
    }

    /** AIFF-C COMM: the same plus the `NONE` compression type and its pascal-string name. */
    fun commC(frames: Long = 1000): ByteArray {
        val rate = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
        val name = byteArrayOf(14) + ascii("not compressed") + byteArrayOf(0)
        return chunk("COMM", joinBytes(be16(2), be32(frames), be16(16), rate, ascii("NONE"), name))
    }

    /** SSND with [leadBytes] of unused bytes before the sound (the `offset` field) and a block size. */
    fun ssnd(sound: ByteArray, leadBytes: ByteArray = ByteArray(0), blockSize: Long = 0): ByteArray =
        chunk("SSND", joinBytes(be32(leadBytes.size.toLong()), be32(blockSize), leadBytes, sound))

    fun form(vararg chunks: ByteArray, type: String = "AIFF"): ByteArray {
        val body = joinBytes(*chunks)
        return joinBytes(ascii("FORM"), be32(4L + body.size), ascii(type), body)
    }
}

internal fun be64(value: Long) = be32(value ushr 32) + be32(value and 0xFFFFFFFFL)

internal object Mp4Bytes {

    fun box(type: String, body: ByteArray = ByteArray(0)): ByteArray = joinBytes(be32(8L + body.size), ascii(type), body)

    /** A box with the 64-bit `largesize` form: size field 1, then the type and the 8-byte size. */
    fun box64(type: String, body: ByteArray): ByteArray = joinBytes(be32(1), ascii(type), be64(16L + body.size), body)

    /** A box whose size field is 0: it runs to the end of the file. */
    fun boxToEof(type: String, body: ByteArray): ByteArray = joinBytes(be32(0), ascii(type), body)

    fun ftyp(brand: String = "M4A "): ByteArray =
        box("ftyp", joinBytes(ascii(brand), be32(0), ascii("M4A "), ascii("mp42"), ascii("isom")))

    fun mdat(payload: ByteArray): ByteArray = box("mdat", payload)

    /**
     * A `moov` with a movie header, one track whose `stco` holds [chunkOffset], and, when [tagBytes] is above 0, a
     * `udta/meta/ilst` tree with that many bytes of tags: the part a tag editor grows and shrinks.
     */
    fun moov(tagBytes: Int = 0, chunkOffset: Long = 0, seed: Int = 50): ByteArray {
        val stco = box("stco", joinBytes(be32(0), be32(1), be32(chunkOffset)))
        val stbl = box("stbl", joinBytes(box("stsd", filler(60, seed)), stco))
        val trak = box("trak", box("mdia", box("minf", stbl)))
        val udta = if (tagBytes > 0) {
            val ilst = box("ilst", filler(tagBytes, seed + 1))
            listOf(box("udta", box("meta", joinBytes(be32(0), box("hdlr", filler(30, seed + 2)), ilst))))
        } else {
            emptyList()
        }
        return box("moov", joinBytes(box("mvhd", filler(100, seed + 3)), trak, *udta.toTypedArray()))
    }

    fun free(size: Int): ByteArray = box("free", ByteArray(size))
}

internal object OggBytes {

    private val CRC_TABLE = IntArray(256) { n ->
        var crc = n shl 24
        repeat(8) { crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        crc
    }

    /** The Ogg page checksum: CRC-32, polynomial 0x04C11DB7, no reflection, no final xor. */
    private fun crc(bytes: ByteArray): Long {
        var crc = 0
        for (b in bytes) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc.toLong() and 0xFFFFFFFFL
    }

    const val CONTINUED = 0x01
    const val FIRST = 0x02
    const val LAST = 0x04

    /** One page with a raw segment table (not checked against [data]) and its checksum filled in. */
    fun page(serial: Long, sequence: Long, flags: Int, lacing: ByteArray, data: ByteArray, granule: Long = sequence * 960): ByteArray {
        val header = joinBytes(
            ascii("OggS"), byteArrayOf(0, flags.toByte()), le64(granule), le32(serial), le32(sequence),
            ByteArray(4), byteArrayOf(lacing.size.toByte()), lacing,
        )
        val page = joinBytes(header, data)
        le32(crc(page)).copyInto(page, 22)
        return page
    }

    private class Segment(val lace: Int, val data: ByteArray, val packet: Int)

    /** The lacing values of a packet of [size] bytes: 255 per full segment, then a value below 255 (0 after a multiple). */
    fun lacing(size: Int): List<Int> = List(size / 255) { 255 } + (size % 255)

    /**
     * [packets] cut into pages of at most [segmentsPerPage] segments, and also after each packet index in [breakAfter],
     * with correct continuation flags, sequence numbers and checksums.
     */
    fun stream(
        packets: List<ByteArray>,
        serial: Long = 0x5EED,
        segmentsPerPage: Int = 255,
        breakAfter: Set<Int> = emptySet(),
        firstSequence: Long = 0,
    ): ByteArray {
        val segments = ArrayList<Segment>()
        packets.forEachIndexed { index, packet ->
            var offset = 0
            for (lace in lacing(packet.size)) {
                segments += Segment(lace, packet.copyOfRange(offset, offset + lace), index)
                offset += lace
            }
        }
        val pages = ArrayList<List<Segment>>()
        var current = ArrayList<Segment>()
        for (segment in segments) {
            current += segment
            if (current.size == segmentsPerPage || (segment.lace < 255 && segment.packet in breakAfter)) {
                pages += current
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) pages += current
        return joinBytes(
            *Array(pages.size) { i ->
                val segs = pages[i]
                val continued = i > 0 && pages[i - 1].last().lace == 255
                val flags = (if (continued) CONTINUED else 0) or (if (i == 0) FIRST else 0) or (if (i == pages.lastIndex) LAST else 0)
                page(
                    serial, firstSequence + i, flags,
                    ByteArray(segs.size) { segs[it].lace.toByte() },
                    joinBytes(*Array(segs.size) { segs[it].data }),
                )
            },
        )
    }

    /** Where each page of [bytes] starts, read through the segment tables. */
    fun pageOffsets(bytes: ByteArray): List<Int> {
        val offsets = ArrayList<Int>()
        var position = 0
        while (position < bytes.size) {
            offsets += position
            val segments = bytes[position + 26].toInt() and 0xFF
            var size = 27 + segments
            for (i in 0 until segments) size += bytes[position + 27 + i].toInt() and 0xFF
            position += size
        }
        return offsets
    }

    fun vorbisId(seed: Int = 60): ByteArray = joinBytes(byteArrayOf(1), ascii("vorbis"), filler(23, seed))

    fun vorbisComment(bytes: Int, seed: Int = 61): ByteArray = joinBytes(byteArrayOf(3), ascii("vorbis"), filler(bytes, seed))

    fun vorbisSetup(bytes: Int = 400, seed: Int = 62): ByteArray = joinBytes(byteArrayOf(5), ascii("vorbis"), filler(bytes, seed))

    fun opusHead(seed: Int = 63): ByteArray = joinBytes(ascii("OpusHead"), byteArrayOf(1), filler(10, seed))

    fun opusTags(bytes: Int, seed: Int = 64): ByteArray = joinBytes(ascii("OpusTags"), filler(bytes, seed))

    /** The FLAC-in-Ogg first packet: `0x7F FLAC`, mapping 1.0, [headerPackets] more packets, `fLaC` and STREAMINFO. */
    fun flacId(headerPackets: Int, lastMetadataBlock: Boolean = false, seed: Int = 65): ByteArray =
        joinBytes(
            byteArrayOf(0x7F), ascii("FLAC"), byteArrayOf(1, 0), be16(headerPackets), ascii("fLaC"),
            FlacBytes.block(FlacBytes.STREAMINFO, filler(34, seed), last = lastMetadataBlock),
        )

    /** A FLAC metadata block as its own Ogg packet. */
    fun flacBlock(type: Int, bytes: Int, last: Boolean = false, seed: Int = 66): ByteArray =
        FlacBytes.block(type, filler(bytes, seed), last)

    /** Audio packets of the given [sizes]: filler bytes (no 0xFF), seeded per packet. */
    fun packets(sizes: List<Int>, seed: Int = 200): List<ByteArray> = sizes.mapIndexed { i, size -> filler(size, seed + i) }
}
