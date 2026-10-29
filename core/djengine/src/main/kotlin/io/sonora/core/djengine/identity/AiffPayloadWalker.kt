package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * AIFF and AIFF-C. The payload is the `COMM` chunk body (channels, frames, sample size, sample rate and, in AIFF-C, the
 * compression type) and the sound bytes of the `SSND` chunk, in file order: the 8-byte SSND header and the `offset`
 * bytes before the first sample are skipped, `blockSize` only describes alignment and does not move any byte. Every
 * other chunk (`NAME`, `ANNO`, `MARK`, `ID3 `, `APPL`, ...) is excluded. Chunk sizes are big-endian and odd-sized
 * chunks are followed by a pad byte (Apple, Audio Interchange File Format 1.3; AIFF-C draft).
 */
object AiffPayloadWalker : PayloadWalker {

    override val containerTag: String = "aiff"

    private const val FIRST_CHUNK = 12L
    private const val CHUNK_HEADER_SIZE = 8
    private const val MIN_COMM_SIZE = 18
    private const val SSND_HEADER_SIZE = 8

    override fun matches(prefix: ByteArray): Boolean =
        prefix.size >= 12 && prefix.hasAscii(0, "FORM") && (prefix.hasAscii(8, "AIFF") || prefix.hasAscii(8, "AIFC"))

    override fun payloadRanges(source: ByteSource): List<LongRange>? {
        val length = source.length
        var comm: LongRange? = null
        var sound: LongRange? = null
        val header = ByteArray(CHUNK_HEADER_SIZE)
        var position = FIRST_CHUNK
        while ((comm == null || sound == null) && position + CHUNK_HEADER_SIZE <= length) {
            if (!source.readFully(position, header)) return null
            val body = position + CHUNK_HEADER_SIZE
            val size = header.u32be(4)
            if (size > length - body) return null
            when {
                header.hasAscii(0, "COMM") && comm == null -> {
                    if (size < MIN_COMM_SIZE) return null
                    comm = body until body + size
                }
                header.hasAscii(0, "SSND") && sound == null -> {
                    if (size < SSND_HEADER_SIZE) return null
                    val fields = source.readBytes(body, SSND_HEADER_SIZE) ?: return null
                    val start = body + SSND_HEADER_SIZE + fields.u32be(0)
                    val end = body + size
                    if (start > end) return null
                    sound = start until end
                }
            }
            position = body + size + (size and 1)
        }
        val commRange = comm ?: return null
        val soundRange = sound ?: return null
        if (soundRange.isEmpty()) return null
        return listOf(commRange, soundRange).sortedBy { it.first }
    }
}
