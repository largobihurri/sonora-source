package io.sonora.core.transcoder.mp4

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Copies an MPEG-4 file (what MediaMuxer wrote) from [inject]'s source to its target with the tags added as
 * `moov/udta/meta` (a `hdlr` of type `mdir` and the given `ilst`): an existing `meta` in `udta` is replaced, the
 * rest of `udta` and every other box is kept byte for byte. When the `moov` sits ahead of the media, every chunk
 * offset (`stco`, `co64`) pointing past it moves by the bytes the `moov` grew, so the samples are still found.
 */
object Mp4MetadataInjector {

    /** A `moov` bigger than this is not a music file's (an hour of AAC needs well under 1 MiB of tables). */
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private const val COPY_BUFFER = 64 * 1024
    private val CONTAINERS = setOf("trak", "mdia", "minf", "stbl")

    private class Box(val type: String, val start: Long, val size: Long)

    /**
     * Writes [source] (read from position 0) to [target] at its current position with [ilst] (a complete `ilst` box).
     *
     * @throws IOException when [source] is not an MPEG-4 file with a `moov`, or a moved offset would pass 4 GiB in a
     *   32-bit `stco`.
     */
    fun inject(source: SeekableByteChannel, target: WritableByteChannel, ilst: ByteArray) {
        val boxes = topLevel(source)
        val moov = boxes.firstOrNull { it.type == "moov" } ?: throw IOException("Not an MPEG-4 file: no moov box")
        if (moov.size > MAX_MOOV_BYTES) throw IOException("moov box of ${moov.size} bytes is too large")
        val original = read(source, moov.start, moov.size.toInt())
        if (ByteBuffer.wrap(original).getInt(0).toLong() != moov.size) throw IOException("moov box uses a 64-bit size")
        val tagged = withMeta(original, ilst)
        val growth = (tagged.size - original.size).toLong()
        if (growth != 0L) shiftOffsets(tagged, 8, tagged.size, after = moov.start, by = growth)
        for (box in boxes) {
            if (box === moov) writeFully(target, ByteBuffer.wrap(tagged)) else copy(source, target, box.start, box.size)
        }
    }

    private fun topLevel(source: SeekableByteChannel): List<Box> {
        val boxes = ArrayList<Box>()
        val length = source.size()
        var at = 0L
        while (at < length) {
            if (length - at < 8) throw IOException("Truncated box header at $at")
            val head = ByteBuffer.wrap(read(source, at, 8))
            var size = head.getInt(0).toLong() and 0xFFFF_FFFFL
            val type = String(head.array(), 4, 4, Charsets.ISO_8859_1)
            when (size) {
                0L -> size = length - at
                1L -> size = ByteBuffer.wrap(read(source, at + 8, 8)).getLong(0)
            }
            if (size < 8 || at + size > length) throw IOException("Box '$type' at $at of $size bytes does not fit the file")
            boxes += Box(type, at, size)
            at += size
        }
        return boxes
    }

    /** [moov] with `udta/meta` set to a new `meta` holding [ilst]. */
    private fun withMeta(moov: ByteArray, ilst: ByteArray): ByteArray {
        val meta = Mp4Ilst.box("meta", ByteArray(4) + HDLR + ilst)
        val children = children(moov, 8, moov.size)
        val udta = children.firstOrNull { it.first == "udta" }
        val newUdta = if (udta == null) {
            Mp4Ilst.box("udta", meta)
        } else {
            val kept = children(udta.second, 8, udta.second.size).filter { it.first != "meta" }.map { it.second }
            Mp4Ilst.box("udta", (kept + meta).fold(ByteArray(0), ByteArray::plus))
        }
        val body = children.filter { it.first != "udta" }.map { it.second } + newUdta
        return Mp4Ilst.box("moov", body.fold(ByteArray(0), ByteArray::plus))
    }

    /** The boxes between [from] and [to] of [bytes] as type and complete bytes. */
    private fun children(bytes: ByteArray, from: Int, to: Int): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        val b = ByteBuffer.wrap(bytes)
        var at = from
        while (at + 8 <= to) {
            val size = b.getInt(at)
            if (size < 8 || at + size > to) throw IOException("Malformed box inside moov at $at")
            out += String(bytes, at + 4, 4, Charsets.ISO_8859_1) to bytes.copyOfRange(at, at + size)
            at += size
        }
        return out
    }

    /** Adds [by] to every chunk offset above [after] in the tables between [from] and [to] of [bytes], in place. */
    private fun shiftOffsets(bytes: ByteArray, from: Int, to: Int, after: Long, by: Long) {
        val b = ByteBuffer.wrap(bytes)
        var at = from
        while (at + 8 <= to) {
            val size = b.getInt(at)
            if (size < 8 || at + size > to) throw IOException("Malformed box inside moov at $at")
            when (val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)) {
                in CONTAINERS -> shiftOffsets(bytes, at + 8, at + size, after, by)
                "stco", "co64" -> {
                    val wide = type == "co64"
                    val count = b.getInt(at + 12)
                    val width = if (wide) 8 else 4
                    if (count < 0 || 16L + count.toLong() * width > size) throw IOException("Malformed $type")
                    for (i in 0 until count) {
                        val entry = at + 16 + i * width
                        val offset = if (wide) b.getLong(entry) else b.getInt(entry).toLong() and 0xFFFF_FFFFL
                        if (offset <= after) continue
                        val moved = offset + by
                        if (wide) {
                            b.putLong(entry, moved)
                        } else {
                            if (moved > 0xFFFF_FFFFL) throw IOException("Chunk offset $moved passes the 4 GiB stco limit")
                            b.putInt(entry, moved.toInt())
                        }
                    }
                }
            }
            at += size
        }
    }

    private fun read(source: SeekableByteChannel, at: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        source.position(at)
        while (buffer.hasRemaining()) if (source.read(buffer) < 0) throw IOException("Unexpected end of file at ${source.position()}")
        return buffer.array()
    }

    private fun copy(source: SeekableByteChannel, target: WritableByteChannel, at: Long, length: Long) {
        source.position(at)
        val buffer = ByteBuffer.allocate(COPY_BUFFER)
        var left = length
        while (left > 0) {
            buffer.clear()
            if (left < buffer.capacity()) buffer.limit(left.toInt())
            val read = source.read(buffer)
            if (read < 0) throw IOException("Unexpected end of file at ${source.position()}")
            buffer.flip()
            writeFully(target, buffer)
            left -= read
        }
    }

    private fun writeFully(target: WritableByteChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) target.write(buffer)
    }

    /** `hdlr` of the iTunes metadata: version 0, no pre-defined value, handler `mdir`, reserved `appl`, empty name. */
    private val HDLR: ByteArray = Mp4Ilst.box(
        "hdlr",
        ByteArray(8) + "mdirappl".toByteArray(Charsets.ISO_8859_1) + ByteArray(8) + byteArrayOf(0),
    )
}
