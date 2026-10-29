package io.sonora.core.djloop

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameRingTest {

    /** Frames of 2 bytes: frame `i` is the bytes (i, i + 100). */
    private fun frames(from: Int, count: Int) = ByteArray(count * 2) { (from + it / 2 + if (it % 2 == 1) 100 else 0).toByte() }

    private fun ring(capacity: Int, anchor: Long = 0L) = FrameRing(2, capacity).also { it.clear(anchor) }

    private fun read(ring: FrameRing, from: Long, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        ring.copy(from, count, out, 0)
        return out
    }

    @Test
    fun `it holds what was appended addressed by source frame`() {
        val ring = ring(10, anchor = 500)

        ring.append(frames(0, 6), 0, 6)

        assertThat(ring.startFrame).isEqualTo(500L)
        assertThat(ring.endFrame).isEqualTo(506L)
        assertThat(read(ring, 502, 3)).isEqualTo(frames(2, 3))
    }

    @Test
    fun `the oldest frames are dropped when it is full and reads cross the end of the storage`() {
        val ring = ring(8)
        ring.append(frames(0, 6), 0, 6)
        ring.append(frames(6, 5), 0, 5)

        assertThat(ring.endFrame).isEqualTo(11L)
        assertThat(ring.startFrame).isEqualTo(3L)
        assertThat(read(ring, 3, 8)).isEqualTo(frames(3, 8))
        assertThat(read(ring, 7, 3)).isEqualTo(frames(7, 3))
    }

    @Test
    fun `an append bigger than the ring keeps its last frames`() {
        val ring = ring(4)

        ring.append(frames(0, 10), 0, 10)

        assertThat(ring.endFrame).isEqualTo(10L)
        assertThat(ring.startFrame).isEqualTo(6L)
        assertThat(read(ring, 6, 4)).isEqualTo(frames(6, 4))
    }

    @Test
    fun `growing keeps the frames it holds and takes more`() {
        val ring = ring(5)
        ring.append(frames(0, 8), 0, 8)

        ring.ensureCapacity(12)
        ring.append(frames(8, 6), 0, 6)

        assertThat(ring.capacityFrames).isEqualTo(12)
        assertThat(ring.startFrame).isEqualTo(3L)
        assertThat(read(ring, 3, 11)).isEqualTo(frames(3, 11))
    }

    @Test
    fun `clear empties it and moves the anchor`() {
        val ring = ring(5)
        ring.append(frames(0, 3), 0, 3)

        ring.clear(900)

        assertThat(ring.startFrame).isEqualTo(900L)
        assertThat(ring.endFrame).isEqualTo(900L)
    }

    @Test
    fun `reading frames it does not hold is refused`() {
        val ring = ring(4)
        ring.append(frames(0, 6), 0, 6)

        assertThrows(IllegalArgumentException::class.java) { read(ring, 1, 2) }
        assertThrows(IllegalArgumentException::class.java) { read(ring, 5, 2) }
    }
}
