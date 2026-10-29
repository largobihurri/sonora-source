package io.sonora.core.domain.audio

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmLayout.SampleKind.FLOAT
import io.sonora.core.domain.audio.PcmLayout.SampleKind.SIGNED_INT
import io.sonora.core.domain.audio.PcmLayout.SampleKind.UNSIGNED_INT
import io.sonora.core.domain.audio.PcmSamples.MAX_BELOW_ONE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ByteOrder.BIG_ENDIAN
import java.nio.ByteOrder.LITTLE_ENDIAN
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmSamplesTest {

    private fun convert(layout: PcmLayout, vararg bytes: Int): FloatArray {
        val source = ByteArray(bytes.size) { bytes[it].toByte() }
        val count = source.size / layout.bytesPerSample
        return FloatArray(count).also { PcmSamples.toFloat(source, 0, layout, it, 0, count) }
    }

    private fun int(bits: Int, order: ByteOrder = BIG_ENDIAN) = PcmLayout(SIGNED_INT, bits, order)

    private fun float(bits: Int, order: ByteOrder = BIG_ENDIAN) = PcmLayout(FLOAT, bits, order)

    @Test
    fun `MAX_BELOW_ONE is the float just below one`() {
        assertThat(MAX_BELOW_ONE).isEqualTo(Math.nextDown(1f))
    }

    @Test
    fun `signed 8-bit spans minus one to just below one`() {
        assertThat(convert(PcmLayout(SIGNED_INT, 8, BIG_ENDIAN), 0x80, 0x7F, 0x00, 0xFF))
            .isEqualTo(floatArrayOf(-1f, 127f / 128, 0f, -1f / 128))
    }

    @Test
    fun `unsigned 8-bit is offset binary centred on 0x80`() {
        assertThat(convert(PcmLayout(UNSIGNED_INT, 8, BIG_ENDIAN), 0x00, 0xFF, 0x80, 0x7F))
            .isEqualTo(floatArrayOf(-1f, 127f / 128, 0f, -1f / 128))
    }

    @Test
    fun `16-bit big-endian boundaries and sign`() {
        assertThat(convert(int(16), 0x80, 0x00, 0x7F, 0xFF, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0x01))
            .isEqualTo(floatArrayOf(-1f, 32_767f / 32_768, 0f, -1f / 32_768, 1f / 32_768))
    }

    @Test
    fun `16-bit little-endian swaps the byte order`() {
        assertThat(convert(int(16, LITTLE_ENDIAN), 0x00, 0x80, 0xFF, 0x7F, 0x00, 0x00, 0xFF, 0xFF, 0x01, 0x00))
            .isEqualTo(floatArrayOf(-1f, 32_767f / 32_768, 0f, -1f / 32_768, 1f / 32_768))
    }

    @Test
    fun `24-bit big-endian converts exactly, including the smallest step`() {
        val step = 1f / 8_388_608
        assertThat(convert(int(24), 0x80, 0x00, 0x00, 0x7F, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x01))
            .isEqualTo(floatArrayOf(-1f, 8_388_607 * step, 0f, -step, step))
        assertThat(8_388_607 * step).isLessThan(1f)
    }

    @Test
    fun `24-bit little-endian swaps the byte order`() {
        val step = 1f / 8_388_608
        assertThat(convert(int(24, LITTLE_ENDIAN), 0x00, 0x00, 0x80, 0xFF, 0xFF, 0x7F, 0xFF, 0xFF, 0xFF, 0x01, 0x00, 0x00))
            .isEqualTo(floatArrayOf(-1f, 8_388_607 * step, -step, step))
    }

    @Test
    fun `32-bit big-endian keeps the sign and clips full scale below one`() {
        assertThat(convert(int(32), 0x80, 0x00, 0x00, 0x00, 0x7F, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00))
            .isEqualTo(floatArrayOf(-1f, MAX_BELOW_ONE, 0f, -256f / 2_147_483_648f))
    }

    @Test
    fun `32-bit little-endian swaps the byte order`() {
        assertThat(convert(int(32, LITTLE_ENDIAN), 0x00, 0x00, 0x00, 0x80, 0x00, 0x01, 0x00, 0x00))
            .isEqualTo(floatArrayOf(-1f, 256f / 2_147_483_648f))
    }

    @Test
    fun `32-bit integer loses only the lowest 8 bits`() {
        // 0x40000001 and 0x40000000 differ in bit 0 only: float32 cannot tell them apart.
        assertThat(convert(int(32), 0x40, 0x00, 0x00, 0x01, 0x40, 0x00, 0x00, 0x00)).isEqualTo(floatArrayOf(0.5f, 0.5f))
    }

    @Test
    fun `integer samples narrower than their container are left-justified, so they scale by the container`() {
        // A 12-bit sample stored in 16 bits: 0x7FF0 is its maximum, 0x8000 its minimum.
        assertThat(convert(int(16), 0x7F, 0xF0, 0x80, 0x00)).isEqualTo(floatArrayOf(32_752f / 32_768, -1f))
    }

    @Test
    fun `float32 passes through in range and clips outside it`() {
        val values = floatArrayOf(0.5f, -0.25f, 1f, 1.5f, -1f, -2f, Float.NaN, Float.POSITIVE_INFINITY)
        for (order in listOf(BIG_ENDIAN, LITTLE_ENDIAN)) {
            val bytes = ByteBuffer.allocate(values.size * 4).order(order).apply { values.forEach { putFloat(it) } }.array()
            val target = FloatArray(values.size)
            PcmSamples.toFloat(bytes, 0, float(32, order), target, 0, values.size)

            assertThat(target).isEqualTo(floatArrayOf(0.5f, -0.25f, MAX_BELOW_ONE, MAX_BELOW_ONE, -1f, -1f, 0f, MAX_BELOW_ONE))
        }
    }

    @Test
    fun `float64 is narrowed and clipped`() {
        val values = doubleArrayOf(0.25, -0.75, 1.0, -1.000001, Double.NaN)
        for (order in listOf(BIG_ENDIAN, LITTLE_ENDIAN)) {
            val bytes = ByteBuffer.allocate(values.size * 8).order(order).apply { values.forEach { putDouble(it) } }.array()
            val target = FloatArray(values.size)
            PcmSamples.toFloat(bytes, 0, float(64, order), target, 0, values.size)

            assertThat(target).isEqualTo(floatArrayOf(0.25f, -0.75f, MAX_BELOW_ONE, -1f, 0f))
        }
    }

    @Test
    fun `offsets and count bound what is read and written`() {
        val source = byteArrayOf(0x11, 0x40, 0x00, 0xC0.toByte(), 0x00, 0x7F)
        val target = FloatArray(4) { 9f }

        PcmSamples.toFloat(source, 1, int(16), target, 1, 2)

        assertThat(target).isEqualTo(floatArrayOf(9f, 0.5f, -0.5f, 9f))
    }

    @Test
    fun `out of range regions are rejected`() {
        assertThrows(IndexOutOfBoundsException::class.java) {
            PcmSamples.toFloat(ByteArray(4), 0, int(16), FloatArray(4), 0, 3)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            PcmSamples.toFloat(ByteArray(8), 0, int(16), FloatArray(4), 2, 3)
        }
    }

    @Test
    fun `clip maps any float into the normalised range`() {
        assertThat(PcmSamples.clip(0.3f)).isEqualTo(0.3f)
        assertThat(PcmSamples.clip(1f)).isEqualTo(MAX_BELOW_ONE)
        assertThat(PcmSamples.clip(-1f)).isEqualTo(-1f)
        assertThat(PcmSamples.clip(-3f)).isEqualTo(-1f)
        assertThat(PcmSamples.clip(Float.NaN)).isEqualTo(0f)
        assertThat(PcmSamples.clip(Float.NEGATIVE_INFINITY)).isEqualTo(-1f)
    }

    @Test
    fun `downmix averages the channels of each frame`() {
        val target = FloatArray(2)

        PcmSamples.downmixToMono(floatArrayOf(1f, 0f, 0.5f, -0.5f), frames = 2, channelCount = 2, target = target)

        assertThat(target).isEqualTo(floatArrayOf(0.5f, 0f))
    }

    @Test
    fun `downmix handles mono, more channels and partial buffers`() {
        val mono = FloatArray(3) { 7f }
        PcmSamples.downmixToMono(floatArrayOf(0.1f, 0.2f, 0.3f), frames = 2, channelCount = 1, target = mono)
        assertThat(mono).isEqualTo(floatArrayOf(0.1f, 0.2f, 7f))

        val surround = FloatArray(1)
        PcmSamples.downmixToMono(floatArrayOf(0.3f, 0.6f, 0.9f, 5f), frames = 1, channelCount = 3, target = surround)
        assertThat(surround[0]).isWithin(1e-6f).of(0.6f)
    }

    @Test
    fun `downmix can write over its own input`() {
        val samples = floatArrayOf(0.2f, 0.4f, -0.2f, -0.6f, 1f, 0f)

        PcmSamples.downmixToMono(samples, frames = 3, channelCount = 2, target = samples)

        assertThat(samples.copyOf(3)).usingTolerance(1e-6).containsExactly(0.3f, -0.4f, 0.5f).inOrder()
    }

    @Test
    fun `downmix rejects too small buffers and bad channel counts`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmSamples.downmixToMono(FloatArray(4), frames = 2, channelCount = 2, target = FloatArray(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmSamples.downmixToMono(FloatArray(4), frames = 3, channelCount = 2, target = FloatArray(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmSamples.downmixToMono(FloatArray(4), frames = 1, channelCount = 0, target = FloatArray(1))
        }
    }

    @Test
    fun `layouts map to the source sample format`() {
        assertThat(PcmLayout(UNSIGNED_INT, 8, BIG_ENDIAN).toSampleFormat()).isEqualTo(PcmSampleFormat.INT8)
        assertThat(int(8).toSampleFormat()).isEqualTo(PcmSampleFormat.INT8)
        assertThat(int(16, LITTLE_ENDIAN).toSampleFormat()).isEqualTo(PcmSampleFormat.INT16)
        assertThat(int(24).toSampleFormat()).isEqualTo(PcmSampleFormat.INT24)
        assertThat(int(32).toSampleFormat()).isEqualTo(PcmSampleFormat.INT32)
        assertThat(float(32).toSampleFormat()).isEqualTo(PcmSampleFormat.FLOAT32)
        assertThat(float(64).toSampleFormat()).isEqualTo(PcmSampleFormat.FLOAT64)
    }
}
