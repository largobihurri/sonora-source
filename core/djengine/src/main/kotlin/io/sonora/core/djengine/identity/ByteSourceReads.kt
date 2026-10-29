package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * Reads exactly [count] bytes at [position] into [buffer], looping over short reads. Returns `false` (never throws)
 * when the source ends first or [position] is negative, so a truncated file is a malformed container, not an error.
 * I/O errors from the source propagate.
 */
internal fun ByteSource.readFully(position: Long, buffer: ByteArray, count: Int = buffer.size): Boolean {
    if (position < 0 || count < 0 || count > buffer.size) return false
    var done = 0
    while (done < count) {
        val read = read(position + done, buffer, done, count - done)
        if (read <= 0) return false
        done += read
    }
    return true
}

/** [count] bytes at [position], or `null` when the source does not hold them. */
internal fun ByteSource.readBytes(position: Long, count: Int): ByteArray? =
    ByteArray(count).takeIf { readFully(position, it) }

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u32be(index: Int): Long =
    (u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong()

/** Big-endian 64 bits; negative for values of 2^63 and above, which no real file size reaches. */
internal fun ByteArray.u64be(index: Int): Long = (u32be(index) shl 32) or u32be(index + 4)

internal fun ByteArray.u32le(index: Int): Long =
    u8(index).toLong() or (u8(index + 1).toLong() shl 8) or (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)

/** Little-endian 64 bits; negative for values of 2^63 and above, which no real file size reaches. */
internal fun ByteArray.u64le(index: Int): Long = u32le(index) or (u32le(index + 4) shl 32)

/** Whether the four ASCII characters of [id] are at [index]. */
internal fun ByteArray.hasAscii(index: Int, id: String): Boolean {
    if (index < 0 || index + id.length > size) return false
    for (i in id.indices) if (this[index + i].toInt() != id[i].code) return false
    return true
}
