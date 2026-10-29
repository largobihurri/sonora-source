package io.sonora.core.transcoder.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TpdfQuantizerLittleEndianTest {

    @Test
    fun `samples are written as little endian 16-bit pairs`() {
        val q = TpdfQuantizer(seed = 1, dither = false)
        val samples = floatArrayOf(0f, 0.5f, -0.5f, -1f, 12_345 / 32_768f)
        val target = ByteArray(10)

        q.quantizeToLittleEndian(samples, 5, target)

        assertThat(target).isEqualTo(
            byteArrayOf(0x00, 0x00, 0x00, 0x40, 0x00, 0xC0.toByte(), 0x00, 0x80.toByte(), 0x39, 0x30),
        )
    }

    @Test
    fun `the little endian and big endian forms hold the same values`() {
        val q1 = TpdfQuantizer(seed = 3, dither = true)
        val q2 = TpdfQuantizer(seed = 3, dither = true)
        val samples = FloatArray(1_000) { (it - 500) / 520f }
        val little = ByteArray(2_000)
        val big = ByteArray(2_000)

        q1.quantizeToLittleEndian(samples, 1_000, little)
        q2.quantizeToBigEndian(samples, 1_000, big)

        for (i in 0 until 1_000) {
            assertThat(little[i * 2]).isEqualTo(big[i * 2 + 1])
            assertThat(little[i * 2 + 1]).isEqualTo(big[i * 2])
        }
    }

    @Test
    fun `the target must hold the samples`() {
        val q = TpdfQuantizer(seed = 1, dither = false)

        assertThrows(IllegalArgumentException::class.java) { q.quantizeToLittleEndian(FloatArray(4), 4, ByteArray(7)) }
        assertThrows(IllegalArgumentException::class.java) { q.quantizeToLittleEndian(FloatArray(3), 4, ByteArray(8)) }
    }
}
