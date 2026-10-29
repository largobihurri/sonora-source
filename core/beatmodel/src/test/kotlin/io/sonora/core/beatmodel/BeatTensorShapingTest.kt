package io.sonora.core.beatmodel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.MelSpectrogram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class BeatTensorShapingTest {

    private val descriptor = BeatThisModel.descriptor()

    private fun mel(frames: Int, bins: Int = 128, fps: Double = 50.0, value: (Int) -> Float = { it.toFloat() }) =
        MelSpectrogram(bins, fps, FloatArray(frames * bins) { value(it) })

    private fun direct(capacity: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    @Test
    fun `chunks from one to the maximum number of frames are accepted`() {
        for (frames in listOf(1, 2, 5, 20, 299, 300)) BeatTensorShaping.checkChunk(mel(frames), descriptor)
    }

    @Test
    fun `a chunk of more than the maximum number of frames is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) { BeatTensorShaping.checkChunk(mel(301), descriptor) }
        assertThat(e).hasMessageThat().contains("301")
        assertThat(e).hasMessageThat().contains("1 to 300")
    }

    @Test
    fun `an empty chunk is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BeatTensorShaping.checkChunk(mel(0), descriptor) }
    }

    @Test
    fun `a chunk with another bin count or frame rate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BeatTensorShaping.checkChunk(mel(10, bins = 64), descriptor) }
        assertThrows(IllegalArgumentException::class.java) { BeatTensorShaping.checkChunk(mel(10, fps = 100.0), descriptor) }
    }

    @Test
    fun `the input shape is one batch of frames by bins`() {
        assertThat(BeatTensorShaping.inputShape(1_500, 128)).isEqualTo(longArrayOf(1, 1_500, 128))
        assertThat(BeatTensorShaping.inputShape(1, 128)).isEqualTo(longArrayOf(1, 1, 128))
    }

    @Test
    fun `the input is written frame-major and the buffer holds exactly the chunk`() {
        val chunk = mel(3) { it * 0.5f }
        val target = direct(10 * 128)

        val frames = BeatTensorShaping.writeInput(chunk, FloatArray(10 * 128), target)

        assertThat(frames).isEqualTo(3)
        assertThat(target.position()).isEqualTo(0)
        assertThat(target.remaining()).isEqualTo(3 * 128)
        for (frame in 0 until 3) for (bin in 0 until 128) assertThat(target.get(frame * 128 + bin)).isEqualTo(chunk[frame, bin])
    }

    @Test
    fun `a shorter chunk written after a longer one leaves nothing of the longer one in the window`() {
        val staging = FloatArray(10 * 128)
        val target = direct(10 * 128)
        BeatTensorShaping.writeInput(mel(10) { 7f }, staging, target)

        val frames = BeatTensorShaping.writeInput(mel(2) { 1f }, staging, target)

        assertThat(frames).isEqualTo(2)
        assertThat(target.remaining()).isEqualTo(2 * 128)
        val seen = FloatArray(target.remaining()).also { target.duplicate().get(it) }
        assertThat(seen.toSet()).containsExactly(1f)
    }

    @Test
    fun `a value that is not finite is rejected`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val chunk = mel(2) { if (it == 130) bad else 1f }
            assertThrows(IllegalArgumentException::class.java) {
                BeatTensorShaping.writeInput(chunk, FloatArray(2 * 128), direct(2 * 128))
            }
        }
    }

    @Test
    fun `an output of the right shape is read as it is`() {
        val values = direct(4).also { buffer -> floatArrayOf(-1f, 0f, 2.5f, -9.75f).forEach { buffer.put(it) }; buffer.flip() }

        val read = BeatTensorShaping.readOutput("beat", longArrayOf(1, 4), values, frames = 4)

        assertThat(read).isEqualTo(floatArrayOf(-1f, 0f, 2.5f, -9.75f))
    }

    @Test
    fun `an output of another shape or length is an error that names the output`() {
        val four = { direct(4).also { it.put(floatArrayOf(0f, 0f, 0f, 0f)); it.flip() } }
        for (shape in listOf(longArrayOf(4), longArrayOf(2, 4), longArrayOf(1, 3), longArrayOf(1, 4, 1))) {
            val e = assertThrows(IllegalStateException::class.java) { BeatTensorShaping.readOutput("downbeat", shape, four(), 4) }
            assertThat(e).hasMessageThat().contains("downbeat")
        }
        val e = assertThrows(IllegalStateException::class.java) {
            BeatTensorShaping.readOutput("beat", longArrayOf(1, 5), four(), 5)
        }
        assertThat(e).hasMessageThat().contains("beat")
    }

    @Test
    fun `an output with a value that is not finite is an error`() {
        val values = direct(2).also { it.put(floatArrayOf(0f, Float.NaN)); it.flip() }
        assertThrows(IllegalStateException::class.java) { BeatTensorShaping.readOutput("beat", longArrayOf(1, 2), values, 2) }
    }
}
