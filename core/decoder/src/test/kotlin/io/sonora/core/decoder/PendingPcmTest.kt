package io.sonora.core.decoder

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.audio.PcmLayout
import io.sonora.core.domain.audio.PcmLayout.SampleKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Test

class PendingPcmTest {

    private val int16Le = PcmLayout(SampleKind.SIGNED_INT, 16, ByteOrder.LITTLE_ENDIAN)
    private val floatLe = PcmLayout(SampleKind.FLOAT, 32, ByteOrder.LITTLE_ENDIAN)

    private fun int16Le(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) } }.array()

    @Test
    fun `starts empty and drains nothing`() {
        val pending = PendingPcm(channelCount = 2)

        assertThat(pending.isEmpty).isTrue()
        assertThat(pending.drainTo(FloatArray(8))).isEqualTo(0)
    }

    private fun floatLe(vararg samples: Float): ByteArray =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putFloat(it) } }.array()

    private fun PendingPcm.drainAll(): List<Float> {
        val all = mutableListOf<Float>()
        val buffer = FloatArray(6)
        while (true) {
            val frames = drainTo(buffer)
            if (frames == 0) return all
            all += buffer.take(frames * 2)
        }
    }

    @Test
    fun `the first skipped frames are dropped even across blocks`() {
        val pending = PendingPcm(channelCount = 2, skipFrames = 3)
        pending.append(floatLe(0f, 0f, 0.1f, 0.1f), 0, 16, floatLe)
        assertThat(pending.isEmpty).isTrue()
        pending.append(floatLe(0.2f, 0.2f, 0.3f, 0.3f, 0.4f, 0.4f), 0, 24, floatLe)

        assertThat(pending.drainAll()).containsExactly(0.3f, 0.3f, 0.4f, 0.4f).inOrder()
    }

    @Test
    fun `the last cut frames are held back until the end of the stream and then dropped`() {
        val pending = PendingPcm(channelCount = 2, cutFrames = 2)
        pending.append(floatLe(0f, 0f, 0.1f, 0.1f, 0.2f, 0.2f), 0, 24, floatLe)

        assertThat(pending.drainAll()).containsExactly(0f, 0f).inOrder()
        pending.append(floatLe(0.3f, 0.3f), 0, 8, floatLe)
        assertThat(pending.drainAll()).containsExactly(0.1f, 0.1f).inOrder()
        assertThat(pending.isEmpty).isTrue()

        pending.endOfStream()

        assertThat(pending.isEmpty).isTrue()
        assertThat(pending.drainAll()).isEmpty()
    }

    @Test
    fun `a stream shorter than its skip and cut leaves nothing`() {
        val pending = PendingPcm(channelCount = 2, skipFrames = 2, cutFrames = 2)
        pending.append(floatLe(0f, 0f, 0.1f, 0.1f, 0.2f, 0.2f), 0, 24, floatLe)

        pending.endOfStream()

        assertThat(pending.isEmpty).isTrue()
        assertThat(pending.drainAll()).isEmpty()
    }

    @Test
    fun `hands out a decoded block in buffer-sized whole frames`() {
        val pending = PendingPcm(channelCount = 2)
        val bytes = int16Le(0x4000, -0x4000, 0x2000, -0x2000, 0, 0)
        pending.append(bytes, 0, bytes.size, int16Le)
        val buffer = FloatArray(5)

        assertThat(pending.drainTo(buffer)).isEqualTo(2)
        assertThat(buffer.copyOf(4)).isEqualTo(floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f))
        assertThat(pending.isEmpty).isFalse()
        assertThat(pending.drainTo(buffer)).isEqualTo(1)
        assertThat(buffer.copyOf(2)).isEqualTo(floatArrayOf(0f, 0f))
        assertThat(pending.isEmpty).isTrue()
    }

    @Test
    fun `keeps a partial frame until the rest of it arrives`() {
        val pending = PendingPcm(channelCount = 2)
        val bytes = int16Le(0x4000, -0x4000, 0x2000, -0x2000)

        pending.append(bytes, 0, 5, int16Le)
        val buffer = FloatArray(4)
        assertThat(pending.drainTo(buffer)).isEqualTo(1)
        assertThat(pending.isEmpty).isTrue()

        pending.append(bytes, 5, 3, int16Le)
        assertThat(pending.drainTo(buffer)).isEqualTo(1)
        assertThat(buffer.copyOf(2)).isEqualTo(floatArrayOf(0.25f, -0.25f))
    }

    @Test
    fun `follows a change of encoding between blocks`() {
        val pending = PendingPcm(channelCount = 1)
        val first = int16Le(0x4000)
        val second = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.125f).putFloat(-0.5f).array()

        pending.append(first, 0, first.size, int16Le)
        pending.append(second, 0, second.size, floatLe)
        val buffer = FloatArray(8)

        assertThat(pending.drainTo(buffer)).isEqualTo(3)
        assertThat(buffer.copyOf(3)).isEqualTo(floatArrayOf(0.5f, 0.125f, -0.5f))
    }

    @Test
    fun `grows for large blocks and keeps the unread rest after more arrives`() {
        val pending = PendingPcm(channelCount = 1)
        val big = int16Le(*IntArray(10_000) { it })
        pending.append(big, 0, big.size, int16Le)
        val buffer = FloatArray(4096)
        assertThat(pending.drainTo(buffer)).isEqualTo(4096)

        val more = int16Le(-1)
        pending.append(more, 0, more.size, int16Le)
        var total = 0
        var last = 0f
        while (!pending.isEmpty) {
            val frames = pending.drainTo(buffer)
            total += frames
            last = buffer[frames - 1]
        }

        assertThat(total).isEqualTo(10_000 - 4096 + 1)
        assertThat(last).isEqualTo(-1f / 32_768)
    }
}
