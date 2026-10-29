package io.sonora.core.transcoder.ogg

/**
 * The CRC-32 of Ogg pages (RFC 3533 section 6): generator polynomial 0x04C11DB7, initial value 0, bits processed
 * most significant first with no reflection, and no final inversion. The checksum covers the whole page with its own
 * CRC field set to zero.
 */
object OggCrc {

    private val TABLE = IntArray(256) { index ->
        var crc = index shl 24
        repeat(8) { crc = if (crc < 0) (crc shl 1) xor POLYNOMIAL else crc shl 1 }
        crc
    }

    private const val POLYNOMIAL = 0x04C11DB7

    /** Continues [crc] over [length] bytes of [bytes] from [offset]; start from 0. */
    fun update(crc: Int, bytes: ByteArray, offset: Int, length: Int): Int {
        var value = crc
        for (i in offset until offset + length) {
            value = (value shl 8) xor TABLE[((value ushr 24) xor bytes[i].toInt()) and 0xFF]
        }
        return value
    }

    fun of(bytes: ByteArray): Int = update(0, bytes, 0, bytes.size)
}
