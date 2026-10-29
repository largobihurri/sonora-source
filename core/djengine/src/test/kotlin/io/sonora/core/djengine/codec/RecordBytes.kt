package io.sonora.core.djengine.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Builds raw SDJ1 records by hand, independently of [DjAnalysisCodec], so tests can craft damaged or foreign records
 * (valid checksum, wrong content) and so the layout is written down a second time.
 *
 * Layout, all integers big-endian: `"SDJ1"`, format version u16, kind u8, payload, CRC-32 of everything before it u32.
 */
internal object RecordBytes {
    const val KIND_GRID = 1
    const val KIND_WAVEFORM = 2
    const val KIND_BACKUP = 3

    val MAGIC = byteArrayOf(0x53, 0x44, 0x4A, 0x31)

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun record(kind: Int, payload: ByteArray, version: Int = 1, magic: ByteArray = MAGIC): ByteArray {
        val body = ByteBuffer.allocate(magic.size + 3 + payload.size).order(ByteOrder.BIG_ENDIAN)
        body.put(magic).putShort(version.toShort()).put(kind.toByte()).put(payload)
        return withCrc(body.array())
    }

    /** [bytesWithoutCrc] followed by their CRC-32. */
    fun withCrc(bytesWithoutCrc: ByteArray): ByteArray {
        val crc = CRC32().apply { update(bytesWithoutCrc) }.value
        return bytesWithoutCrc + ByteBuffer.allocate(4).putInt(crc.toInt()).array()
    }

    /** Recomputes the trailing CRC of [record] after the test changed some of its bytes. */
    fun refreshCrc(record: ByteArray): ByteArray = withCrc(record.copyOf(record.size - 4))

    fun payload(build: PayloadBuilder.() -> Unit): ByteArray = PayloadBuilder().apply(build).toByteArray()

    class PayloadBuilder {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = apply { out.write(v) }

        fun u16(v: Int) = apply { out.write(ByteBuffer.allocate(2).putShort(v.toShort()).array()) }

        fun u32(v: Long) = apply { out.write(ByteBuffer.allocate(4).putInt(v.toInt()).array()) }

        fun i64(v: Long) = apply { out.write(ByteBuffer.allocate(8).putLong(v).array()) }

        fun f64(v: Double) = apply { out.write(ByteBuffer.allocate(8).putLong(java.lang.Double.doubleToRawLongBits(v)).array()) }

        fun string(v: String) = apply {
            val bytes = v.toByteArray(Charsets.UTF_8)
            u16(bytes.size)
            out.write(bytes)
        }

        fun raw(v: ByteArray) = apply { out.write(v) }

        fun segment(startMs: Double, bpm: Double, beatInBar: Int) = apply { f64(startMs).f64(bpm).u8(beatInBar) }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
