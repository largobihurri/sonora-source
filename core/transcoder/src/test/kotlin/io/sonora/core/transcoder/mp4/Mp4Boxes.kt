package io.sonora.core.transcoder.mp4

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** A box of an MPEG-4 file as tests build and read them: its type and payload (after the 8-byte header). */
data class TestBox(val type: String, val payload: ByteArray)

/** Builds and parses MPEG-4 boxes in tests. */
object Mp4Boxes {

    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }

    fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()

    fun longs(vararg values: Long): ByteArray = ByteBuffer.allocate(values.size * 8).apply { values.forEach { putLong(it) } }.array()

    /** `stco` with [offsets]. */
    fun stco(vararg offsets: Int) = box("stco", ints(0, offsets.size, *offsets))

    /** `co64` with [offsets]. */
    fun co64(vararg offsets: Long) = box("co64", ints(0, offsets.size), longs(*offsets))

    /** ftyp, a moov whose single track holds [chunkOffsets] (built from the moov's final position), and the mdat. */
    fun file(moovFirst: Boolean, mdat: ByteArray, udta: ByteArray? = null, sixtyFour: Boolean = false): ByteArray {
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1), ints(0))
        fun moov(mdatPayloadAt: Int): ByteArray {
            val offsets = intArrayOf(mdatPayloadAt, mdatPayloadAt + 4)
            val table = if (sixtyFour) co64(*offsets.map(Int::toLong).toLongArray()) else stco(*offsets)
            val stbl = box("stbl", box("stsd", ints(0, 0)), table)
            val trak = box("trak", box("tkhd", ints(0)), box("mdia", box("mdhd", ints(0)), box("minf", box("smhd", ints(0)), stbl)))
            return box("moov", box("mvhd", ints(0)), trak, *listOfNotNull(udta).toTypedArray())
        }
        val mdatBox = box("mdat", mdat)
        return if (moovFirst) {
            val size = moov(0).size
            ftyp + moov(ftyp.size + size + 8) + mdatBox
        } else {
            ftyp + mdatBox + moov(ftyp.size + 8)
        }
    }

    fun parse(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): List<TestBox> {
        val out = ArrayList<TestBox>()
        var at = from
        while (at < to) {
            val size = ByteBuffer.wrap(bytes, at, 4).int
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            out += TestBox(type, bytes.copyOfRange(at + 8, at + size))
            at += size
        }
        return out
    }

    /** The box at [path] (types separated by `/`) inside [bytes]; `meta` children start after its 4-byte version. */
    fun find(bytes: ByteArray, path: String): TestBox? {
        var boxes = parse(bytes)
        var found: TestBox? = null
        for (type in path.split('/')) {
            found = boxes.firstOrNull { it.type == type } ?: return null
            boxes = if (found.type == "meta") parse(found.payload, 4) else runCatching { parse(found.payload) }.getOrDefault(emptyList())
        }
        return found
    }

    /** The chunk offsets of the file's first `stco` or `co64`. */
    fun chunkOffsets(bytes: ByteArray): List<Long> {
        val base = "moov/trak/mdia/minf/stbl"
        find(bytes, "$base/stco")?.let { box ->
            val b = ByteBuffer.wrap(box.payload)
            val count = b.getInt(4)
            return List(count) { b.getInt(8 + it * 4).toLong() }
        }
        val box = find(bytes, "$base/co64") ?: return emptyList()
        val b = ByteBuffer.wrap(box.payload)
        return List(b.getInt(4)) { b.getLong(8 + it * 8) }
    }

    fun concat(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
}
