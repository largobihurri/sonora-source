package io.sonora.core.playback.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic AIFF and WAV files for extractor and player tests. */
internal object TestAudioFiles {

    /** One extra chunk placed before or after the sound data. */
    class Chunk(val id: String, val body: ByteArray)

    fun aiff(
        channels: Int,
        rate: Int,
        bits: Int,
        data: ByteArray,
        compression: String? = null,
        ssndOffset: Int = 0,
        before: List<Chunk> = emptyList(),
        after: List<Chunk> = emptyList(),
        soundFirst: Boolean = false,
    ): ByteArray {
        val bytesPerFrame = channels * if (compression == "fl32") 4 else if (compression == "fl64") 8 else (bits + 7) / 8
        val comm = ByteArrayOutputStream().apply {
            write(be(channels.toLong(), 2))
            write(be(data.size.toLong() / bytesPerFrame, 4))
            write(be(bits.toLong(), 2))
            write(extended(rate))
            if (compression != null) {
                write(compression.toByteArray(Charsets.US_ASCII))
                write(byteArrayOf(0, 0))
            }
        }.toByteArray()
        val ssnd = be(ssndOffset.toLong(), 4) + be(0, 4) + ByteArray(ssndOffset) + data
        val form = ByteArrayOutputStream()
        form.write((if (compression == null) "AIFF" else "AIFC").toByteArray(Charsets.US_ASCII))
        before.forEach { form.chunk(it.id, it.body) }
        if (soundFirst) {
            form.chunk("SSND", ssnd)
            form.chunk("COMM", comm)
        } else {
            form.chunk("COMM", comm)
            form.chunk("SSND", ssnd)
        }
        after.forEach { form.chunk(it.id, it.body) }
        val body = form.toByteArray()
        return "FORM".toByteArray(Charsets.US_ASCII) + be(body.size.toLong(), 4) + body
    }

    /** A WAV file: `formatType` 1 (PCM), 3 (IEEE float) or 0xFFFE (extensible, with [subFormat]). */
    fun wav(channels: Int, rate: Int, bits: Int, data: ByteArray, formatType: Int, subFormat: Int = 1): ByteArray {
        val blockAlign = channels * bits / 8
        val fmt = ByteBuffer.allocate(if (formatType == 0xFFFE) 40 else 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(formatType.toShort())
            putShort(channels.toShort())
            putInt(rate)
            putInt(rate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bits.toShort())
            if (formatType == 0xFFFE) {
                putShort(22)
                putShort(bits.toShort()) // valid bits
                putInt(if (channels == 2) 3 else 4) // front left + right, or front center
                putShort(subFormat.toShort())
                // The rest of the KSDATAFORMAT_SUBTYPE GUID: 0000-0010-8000-00AA00389B71.
                put(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71))
            }
        }.array()
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        body.riffChunk("fmt ", fmt)
        body.riffChunk("data", data)
        val bytes = body.toByteArray()
        return "RIFF".toByteArray(Charsets.US_ASCII) + le(bytes.size) + bytes
    }

    /** [frames] frames of arbitrary non-silent sample bytes, distinct enough to spot misplaced or reordered data. */
    fun ramp(frames: Int, channels: Int, bytesPerSample: Int): ByteArray =
        ByteArray(frames * channels * bytesPerSample) { (it * 37 + 11).toByte() }

    fun extended(rate: Int): ByteArray {
        val highestBit = 31 - Integer.numberOfLeadingZeros(rate)
        val mantissa = rate.toLong() shl (63 - highestBit)
        return be((16_383L + highestBit), 2) + ByteArray(8) { (mantissa ushr (56 - 8 * it)).toByte() }
    }

    private fun ByteArrayOutputStream.chunk(id: String, body: ByteArray) {
        write(id.toByteArray(Charsets.US_ASCII))
        write(be(body.size.toLong(), 4))
        write(body)
        if (body.size % 2 == 1) write(0)
    }

    private fun ByteArrayOutputStream.riffChunk(id: String, body: ByteArray) {
        write(id.toByteArray(Charsets.US_ASCII))
        write(le(body.size))
        write(body)
        if (body.size % 2 == 1) write(0)
    }

    private fun be(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }

    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
