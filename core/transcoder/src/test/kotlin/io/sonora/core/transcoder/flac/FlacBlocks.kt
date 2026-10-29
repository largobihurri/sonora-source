package io.sonora.core.transcoder.flac

import com.google.common.truth.Truth.assertThat

/** A metadata block of a native FLAC stream as tests read it back. */
data class FlacBlock(val type: Int, val last: Boolean, val body: ByteArray)

/** Test reader of native FLAC streams: the marker, the metadata blocks, and where the frames start. */
object FlacBlocks {

    fun parse(bytes: ByteArray): Pair<List<FlacBlock>, Int> {
        assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("fLaC")
        val blocks = ArrayList<FlacBlock>()
        var at = 4
        while (true) {
            val head = bytes[at].toInt() and 0xFF
            val length = ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            val block = FlacBlock(head and 0x7F, head and 0x80 != 0, bytes.copyOfRange(at + 4, at + 4 + length))
            blocks += block
            at += 4 + length
            if (block.last) return blocks to at
        }
    }

    /** A STREAMINFO body with the given fields, the way an encoder states them before it knows the length. */
    fun streamInfo(sampleRateHz: Int, channels: Int, bits: Int, minBlock: Int = 4_096, maxBlock: Int = 4_096, totalSamples: Long = 0): ByteArray {
        val body = ByteArray(34)
        body[0] = (minBlock shr 8).toByte(); body[1] = minBlock.toByte()
        body[2] = (maxBlock shr 8).toByte(); body[3] = maxBlock.toByte()
        // Minimum and maximum frame sizes (24 bits each) stay 0: unknown.
        val packed = (sampleRateHz.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36) or totalSamples
        for (i in 0 until 8) body[10 + i] = (packed ushr (56 - 8 * i)).toByte()
        return body
    }

    fun totalSamples(streamInfo: ByteArray): Long {
        var packed = 0L
        for (i in 0 until 8) packed = (packed shl 8) or (streamInfo[10 + i].toLong() and 0xFF)
        return packed and 0xF_FFFF_FFFFL
    }

    fun bitsPerSample(streamInfo: ByteArray): Int = (((streamInfo[12].toInt() and 0x01) shl 4) or ((streamInfo[13].toInt() and 0xF0) shr 4)) + 1
}
