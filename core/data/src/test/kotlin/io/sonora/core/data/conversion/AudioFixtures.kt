package io.sonora.core.data.conversion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal valid audio files (silence) that JAudioTagger can read and tag. */
object AudioFixtures {

    fun aiff(frames: Int = 441, channels: Int = 2, rate: Int = 44_100): ByteArray {
        val comm = ByteBuffer.allocate(18).apply {
            putShort(channels.toShort()); putInt(frames); putShort(16); put(extended(rate))
        }.array()
        val ssnd = ByteArray(8) + ByteArray(frames * channels * 2)
        val body = ByteArrayOutputStream().apply {
            write("AIFF".toByteArray())
            write("COMM".toByteArray()); write(be32(comm.size)); write(comm)
            write("SSND".toByteArray()); write(be32(ssnd.size)); write(ssnd)
        }.toByteArray()
        return "FORM".toByteArray() + be32(body.size) + body
    }

    fun wav(frames: Int = 441, channels: Int = 2, rate: Int = 44_100): ByteArray {
        val data = ByteArray(frames * channels * 2)
        val buffer = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        buffer.put("data".toByteArray()).putInt(data.size).put(data)
        return buffer.array()
    }

    /** `fLaC`, a STREAMINFO block (16-bit stereo 44.1 kHz), 1 KiB of padding and 4 KiB standing in for frames. */
    fun flac(totalSamples: Long = 441): ByteArray {
        val info = ByteBuffer.allocate(34)
        info.putShort(4096).putShort(4096)
        info.put(ByteArray(6)) // min/max frame size unknown
        // 20 bits rate, 3 bits channels-1, 5 bits bps-1, 36 bits total samples
        val packed = (44_100L shl 44) or (1L shl 41) or (15L shl 36) or totalSamples
        info.putLong(packed)
        info.put(ByteArray(16)) // MD5 unknown
        val padding = byteArrayOf(0x81.toByte(), 0, 4, 0) + ByteArray(1_024)
        return "fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + info.array() + padding + ByteArray(4_096)
    }

    /** MPEG-1 Layer III, 128 kb/s, 44.1 kHz stereo frames of silence (417 bytes each). */
    fun mp3(frameCount: Int = 40): ByteArray {
        val frame = ByteArray(417).apply { this[0] = 0xFF.toByte(); this[1] = 0xFB.toByte(); this[2] = 0x90.toByte(); this[3] = 0x00 }
        return ByteArrayOutputStream().apply { repeat(frameCount) { write(frame) } }.toByteArray()
    }

    private fun be32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    private fun extended(rate: Int): ByteArray {
        val highestBit = 31 - Integer.numberOfLeadingZeros(rate)
        val mantissa = rate.toLong() shl (63 - highestBit)
        return ByteBuffer.allocate(10).putShort((16_383 + highestBit).toShort()).putLong(mantissa).array()
    }
}
