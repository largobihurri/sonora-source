package io.sonora.core.rekordbox.pdb

/** A growable little-endian byte buffer: `export.pdb` stores every number little-endian. */
internal class LittleEndianBuffer {
    private var bytes = ByteArray(64)

    var size = 0
        private set

    fun u8(value: Int) {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun u16(value: Int) {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        u8(value and 0xFF)
        u8(value ushr 8)
    }

    fun u32(value: Long) {
        require(value in 0..0xFFFF_FFFFL) { "u32 out of range: $value" }
        u16((value and 0xFFFF).toInt())
        u16((value ushr 16).toInt())
    }

    fun u32(value: Int) = u32(value.toLong())

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    fun zeros(count: Int) = bytes(ByteArray(count))

    /** Pads with zeros up to the next multiple of [alignment]; returns the number of bytes added. */
    fun alignTo(alignment: Int): Int {
        val padding = (alignment - size % alignment) % alignment
        zeros(padding)
        return padding
    }

    /** Overwrites the u8 at [offset], already written. */
    fun setU8(offset: Int, value: Int) {
        require(offset < size && value in 0..0xFF) { "bad u8 patch at $offset" }
        bytes[offset] = value.toByte()
    }

    /** Overwrites the u16 at [offset], already written. */
    fun setU16(offset: Int, value: Int) {
        require(offset + 2 <= size && value in 0..0xFFFF) { "bad u16 patch at $offset" }
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }
}

internal inline fun littleEndian(block: LittleEndianBuffer.() -> Unit): ByteArray =
    LittleEndianBuffer().apply(block).toByteArray()

/** [value] rounded up to a multiple of 4: rows and their heap space are 4-byte aligned. */
internal fun align4(value: Int): Int = (value + 3) and 3.inv()
