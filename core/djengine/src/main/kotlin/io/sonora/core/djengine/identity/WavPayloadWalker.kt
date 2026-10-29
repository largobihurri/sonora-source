package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * RIFF/WAVE and RF64/BW64. The payload is the `fmt ` chunk body and the `data` chunk body, in file order; `LIST`,
 * `bext`, `id3 `, `JUNK` and every other chunk are excluded. Chunk bodies are padded to an even size, and the pad byte
 * is never part of a body. A `data` size of 0xFFFFFFFF is taken from the `ds64` chunk (EBU Tech 3306, ITU-R BS.2088),
 * or runs to the end of the file when the file has none (streamed output); a `ds64` table can give the size of other
 * chunks the same way.
 */
object WavPayloadWalker : PayloadWalker {

    override val containerTag: String = "wav"

    private const val FIRST_CHUNK = 12L
    private const val CHUNK_HEADER_SIZE = 8
    private const val UNKNOWN_SIZE = 0xFFFFFFFFL
    private const val MIN_FMT_SIZE = 16
    private const val DS64_FIXED_SIZE = 28
    private const val DS64_ENTRY_SIZE = 12

    override fun matches(prefix: ByteArray): Boolean =
        prefix.size >= 12 && (prefix.hasAscii(0, "RIFF") || prefix.hasAscii(0, "RF64") || prefix.hasAscii(0, "BW64")) &&
            prefix.hasAscii(8, "WAVE")

    override fun payloadRanges(source: ByteSource): List<LongRange>? {
        val length = source.length
        var fmt: LongRange? = null
        var data: LongRange? = null
        var ds64DataSize = -1L
        val tableSizes = HashMap<String, Long>()
        val header = ByteArray(CHUNK_HEADER_SIZE)
        var position = FIRST_CHUNK
        while ((fmt == null || data == null) && position + CHUNK_HEADER_SIZE <= length) {
            if (!source.readFully(position, header)) return null
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            val body = position + CHUNK_HEADER_SIZE
            var size = header.u32le(4)
            if (size == UNKNOWN_SIZE) {
                size = when {
                    id == "data" && ds64DataSize >= 0 -> ds64DataSize
                    id in tableSizes -> tableSizes.getValue(id)
                    id == "data" -> length - body
                    else -> return null
                }
            }
            if (size < 0 || size > length - body) return null
            when {
                id == "ds64" -> {
                    if (size < DS64_FIXED_SIZE) return null
                    val bytes = source.readBytes(body, size.toInt().coerceAtMost(MAX_DS64_BYTES)) ?: return null
                    ds64DataSize = bytes.u64le(8)
                    val entries = minOf(bytes.u32le(24), ((bytes.size - DS64_FIXED_SIZE) / DS64_ENTRY_SIZE).toLong()).toInt()
                    for (i in 0 until entries) {
                        val at = DS64_FIXED_SIZE + i * DS64_ENTRY_SIZE
                        tableSizes[String(bytes, at, 4, Charsets.ISO_8859_1)] = bytes.u64le(at + 4)
                    }
                }
                id == "fmt " && fmt == null -> {
                    if (size < MIN_FMT_SIZE) return null
                    fmt = body until body + size
                }
                id == "data" && data == null -> data = body until body + size
            }
            position = body + size + (size and 1)
        }
        val fmtRange = fmt ?: return null
        val dataRange = data ?: return null
        if (dataRange.isEmpty()) return null
        return listOf(fmtRange, dataRange).sortedBy { it.first }
    }

    private const val MAX_DS64_BYTES = 64 * 1024
}
