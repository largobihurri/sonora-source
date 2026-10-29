package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class F32lePcmSourceTest {

    @Test
    fun `reads interleaved little-endian floats frame by frame`() {
        val samples = floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f, 1f, -1f)
        val source = F32lePcmSource(ByteArrayInputStream(bytes(samples)), sampleRateHz = 44_100, channelCount = 2)

        val buffer = FloatArray(4)
        assertThat(source.read(buffer)).isEqualTo(2)
        assertThat(buffer.toList()).containsExactly(0.5f, -0.5f, 0.25f, -0.25f).inOrder()
        assertThat(source.read(buffer)).isEqualTo(1)
        assertThat(buffer[0]).isEqualTo(1f)
        assertThat(buffer[1]).isEqualTo(-1f)
        assertThat(source.read(buffer)).isEqualTo(-1)
        assertThat(source.read(buffer)).isEqualTo(-1)
    }

    @Test
    fun `a stream that returns one byte at a time still yields whole frames`() {
        val samples = FloatArray(30) { it / 30f - 0.5f }
        val source = F32lePcmSource(TrickleStream(bytes(samples)), sampleRateHz = 48_000, channelCount = 3)

        val out = ArrayList<Float>()
        val buffer = FloatArray(7) // room for two frames of three channels, one sample spare
        while (true) {
            val frames = source.read(buffer)
            if (frames < 0) break
            assertThat(frames).isAtMost(2)
            for (i in 0 until frames * 3) out += buffer[i]
        }
        assertThat(out).containsExactlyElementsIn(samples.toList()).inOrder()
    }

    @Test
    fun `a trailing partial frame is dropped`() {
        val full = bytes(floatArrayOf(0.1f, 0.2f, 0.3f))
        val source = F32lePcmSource(ByteArrayInputStream(full.copyOf(full.size - 2)), sampleRateHz = 44_100, channelCount = 2)
        val buffer = FloatArray(8)
        assertThat(source.read(buffer)).isEqualTo(1)
        assertThat(source.read(buffer)).isEqualTo(-1)
    }

    @Test
    fun `reports format and frame count and cannot restart`() {
        val source = F32lePcmSource(ByteArrayInputStream(ByteArray(0)), sampleRateHz = 96_000, channelCount = 1, totalFrames = 42)
        assertThat(source.sampleRateHz).isEqualTo(96_000)
        assertThat(source.channelCount).isEqualTo(1)
        assertThat(source.totalFrames).isEqualTo(42L)
        assertThat(source.restart()).isFalse()
        assertThat(source.read(FloatArray(4))).isEqualTo(-1)
        assertThat(source.framesRead).isEqualTo(0L)
    }

    @Test
    fun `counts the frames it delivered`() {
        val source = F32lePcmSource(ByteArrayInputStream(bytes(FloatArray(10))), sampleRateHz = 44_100, channelCount = 1)
        val buffer = FloatArray(4)
        while (source.read(buffer) >= 0) Unit
        assertThat(source.framesRead).isEqualTo(10L)
    }

    @Test
    fun `a buffer smaller than one frame is rejected`() {
        val source = F32lePcmSource(ByteArrayInputStream(bytes(FloatArray(4))), sampleRateHz = 44_100, channelCount = 2)
        assertThrows(IllegalArgumentException::class.java) { source.read(FloatArray(1)) }
    }

    private fun bytes(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putFloat(s)
        return buffer.array()
    }

    /** Hands out at most one byte per read call, like a slow pipe. */
    private class TrickleStream(private val data: ByteArray) : InputStream() {
        private var at = 0

        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at >= data.size) return -1
            b[off] = data[at++]
            return 1
        }
    }
}
