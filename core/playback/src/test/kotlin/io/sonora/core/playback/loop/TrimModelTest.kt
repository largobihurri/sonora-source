package io.sonora.core.playback.loop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrimModelTest {

    @Test
    fun `a configured stream is trimmed by its encoder delay`() {
        val trim = TrimModel()
        trim.configured(1_105)

        assertThat(trim.delayFrames).isEqualTo(1_105)
        assertThat(trim.trimmedFrames).isEqualTo(1_105)
    }

    @Test
    fun `the flush before the first audio keeps the trimming, a flush after audio (a seek) takes it off`() {
        val trim = TrimModel()
        trim.configured(1_105)

        trim.flushed()
        assertThat(trim.trimmedFrames).isEqualTo(1_105)

        trim.audioHandled()
        trim.flushed()
        assertThat(trim.trimmedFrames).isEqualTo(0)
        assertThat(trim.delayFrames).isEqualTo(1_105)

        // Later seeks stay untrimmed.
        trim.audioHandled()
        trim.flushed()
        assertThat(trim.trimmedFrames).isEqualTo(0)
    }

    @Test
    fun `the next stream is trimmed again`() {
        val trim = TrimModel()
        trim.configured(1_105)
        trim.audioHandled()
        trim.flushed()

        trim.configured(2_112)

        assertThat(trim.delayFrames).isEqualTo(2_112)
        assertThat(trim.trimmedFrames).isEqualTo(2_112)
    }

    @Test
    fun `a stream without an encoder delay is not trimmed, and a negative one counts as none`() {
        val trim = TrimModel()

        trim.configured(0)
        assertThat(trim.trimmedFrames).isEqualTo(0)

        trim.configured(-1)
        assertThat(trim.delayFrames).isEqualTo(0)
        assertThat(trim.trimmedFrames).isEqualTo(0)
    }
}
