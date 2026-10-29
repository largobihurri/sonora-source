package io.sonora.core.domain.playback.output

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmConverterTest {

    private fun le(bytes: Int) = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun int16s(vararg samples: Int): ByteBuffer = le(samples.size * 2).apply { samples.forEach { putShort(it.toShort()) } }.also { it.flip() }

    private fun floats(vararg samples: Float): ByteBuffer = le(samples.size * 4).apply { samples.forEach { putFloat(it) } }.also { it.flip() }

    private fun convert(input: ByteBuffer, from: OutputEncoding, to: OutputEncoding, channels: Int = 2): ByteArray {
        val frames = input.remaining() / (PcmConverter.bytesPerSample(from) * channels)
        val out = le(frames * PcmConverter.bytesPerSample(to) * channels)
        PcmConverter.convert(input, from, to, channels, frames, out)
        return out.array()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `sample sizes`() {
        assertThat(PcmConverter.bytesPerSample(OutputEncoding.PCM_16)).isEqualTo(2)
        assertThat(PcmConverter.bytesPerSample(OutputEncoding.PCM_24_PACKED)).isEqualTo(3)
        assertThat(PcmConverter.bytesPerSample(OutputEncoding.PCM_32)).isEqualTo(4)
        assertThat(PcmConverter.bytesPerSample(OutputEncoding.PCM_FLOAT)).isEqualTo(4)
    }

    // 16-bit widening.

    @Test
    fun `16-bit samples widen to 24-bit by adding a zero low byte`() {
        val out = convert(int16s(0x1234, -2), OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED, channels = 1)

        assertThat(out).isEqualTo(bytes(0x00, 0x34, 0x12, 0x00, 0xFE, 0xFF))
    }

    @Test
    fun `16-bit samples widen to 32-bit by adding two zero low bytes`() {
        val out = convert(int16s(0x1234, -2), OutputEncoding.PCM_16, OutputEncoding.PCM_32, channels = 1)

        assertThat(out).isEqualTo(bytes(0x00, 0x00, 0x34, 0x12, 0x00, 0x00, 0xFE, 0xFF))
    }

    @Test
    fun `16-bit samples become the float of their value over 32768`() {
        val out = ByteBuffer.wrap(convert(int16s(16_384, -32_768, 32_767), OutputEncoding.PCM_16, OutputEncoding.PCM_FLOAT, channels = 1))
            .order(ByteOrder.LITTLE_ENDIAN)

        assertThat(out.getFloat(0)).isEqualTo(0.5f)
        assertThat(out.getFloat(4)).isEqualTo(-1f)
        assertThat(out.getFloat(8)).isEqualTo(32_767 / 32_768f)
    }

    @Test
    fun `16-bit to 16-bit is a copy`() {
        val source = int16s(1, -1, 32_767, -32_768)
        val expected = ByteArray(8).also { source.duplicate().get(it) }

        assertThat(convert(source, OutputEncoding.PCM_16, OutputEncoding.PCM_16)).isEqualTo(expected)
    }

    // Float to integer: exact for 24-bit content.

    @Test
    fun `float made from any 24-bit integer comes back as exactly that integer`() {
        val random = Random(2026)
        val samples = IntArray(4_000) { random.nextInt(1 shl 24) - (1 shl 23) } + intArrayOf(-(1 shl 23), (1 shl 23) - 1, 0, 1, -1)
        val asFloat = floats(*FloatArray(samples.size) { samples[it] / 8_388_608f })

        val packed = convert(asFloat, OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_24_PACKED, channels = 1)

        for (i in samples.indices) {
            val v = (packed[3 * i].toInt() and 0xFF) or ((packed[3 * i + 1].toInt() and 0xFF) shl 8) or (packed[3 * i + 2].toInt() shl 16)
            assertThat(v).isEqualTo(samples[i])
        }
    }

    @Test
    fun `float made from a 24-bit integer becomes that integer left-justified in 32 bits`() {
        val samples = intArrayOf(0x123456, -0x123456, -(1 shl 23), (1 shl 23) - 1)
        val asFloat = floats(*FloatArray(samples.size) { samples[it] / 8_388_608f })

        val out = ByteBuffer.wrap(convert(asFloat, OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_32, channels = 1)).order(ByteOrder.LITTLE_ENDIAN)

        for (i in samples.indices) assertThat(out.getInt(4 * i)).isEqualTo(samples[i] shl 8)
    }

    @Test
    fun `float beyond full scale is clamped, not wrapped`() {
        val packed = convert(floats(1.5f, -1.5f, 1f), OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_24_PACKED, channels = 1)

        assertThat(packed).isEqualTo(bytes(0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x80, 0xFF, 0xFF, 0x7F))
    }

    @Test
    fun `float to float is a copy`() {
        val source = floats(0.25f, -0.75f)
        val expected = ByteArray(8).also { source.duplicate().get(it) }

        assertThat(convert(source, OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_FLOAT, channels = 1)).isEqualTo(expected)
    }

    // Chunks, positions and refusals.

    @Test
    fun `converting in two chunks gives what one pass gives`() {
        val random = Random(7)
        val source = floats(*FloatArray(64) { (random.nextInt(1 shl 24) - (1 shl 23)) / 8_388_608f })
        val whole = convert(source.duplicate(), OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_24_PACKED)

        val out = le(whole.size)
        val half = source.duplicate()
        PcmConverter.convert(half, OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_24_PACKED, 2, 16, out)
        PcmConverter.convert(half, OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_24_PACKED, 2, 16, out)

        assertThat(out.array()).isEqualTo(whole)
    }

    @Test
    fun `input and output positions advance by the frames converted`() {
        val input = int16s(1, 2, 3, 4, 5, 6)
        val out = le(64)

        PcmConverter.convert(input, OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED, 2, 2, out)

        assertThat(input.position()).isEqualTo(8)
        assertThat(out.position()).isEqualTo(12)
    }

    @Test
    fun `narrowing is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.convert(floats(0f, 0f), OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_16, 2, 1, le(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmConverter.convert(int16s(0, 0), OutputEncoding.OTHER, OutputEncoding.PCM_16, 2, 1, le(8))
        }
    }

    @Test
    fun `it supports exactly the conversions the planner can ask for`() {
        assertThat(PcmConverter.supports(OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED)).isTrue()
        assertThat(PcmConverter.supports(OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_32)).isTrue()
        assertThat(PcmConverter.supports(OutputEncoding.PCM_FLOAT, OutputEncoding.PCM_16)).isFalse()
        assertThat(PcmConverter.supports(OutputEncoding.PCM_32, OutputEncoding.PCM_24_PACKED)).isFalse()
    }
}
