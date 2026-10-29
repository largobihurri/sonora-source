package io.sonora.core.djengine.key

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.DJ_ANALYZER_VERSION
import org.junit.Assert.assertThrows
import org.junit.Test

class ChromaFramesTest {

    @Test
    fun `frames are 36 bins each and frame-major`() {
        val values = FloatArray(72) { it.toFloat() }

        val frames = ChromaFrames(values, framesPerSecond = 10.0)

        assertThat(ChromaFrames.BINS_PER_FRAME).isEqualTo(36)
        assertThat(frames.frameCount).isEqualTo(2)
        assertThat(frames[0, 5]).isEqualTo(5f)
        assertThat(frames[1, 0]).isEqualTo(36f)
        assertThat(frames[1, 35]).isEqualTo(71f)
    }

    @Test
    fun `a partial frame is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ChromaFrames(FloatArray(37), 10.0) }
        assertThrows(IllegalArgumentException::class.java) { ChromaFrames(FloatArray(36), 0.0) }
    }

    @Test
    fun `no frames is valid`() {
        assertThat(ChromaFrames(FloatArray(0), 10.0).frameCount).isEqualTo(0)
    }

    @Test
    fun `the analyzer version is positive`() {
        assertThat(DJ_ANALYZER_VERSION).isAtLeast(1)
    }
}
