package io.sonora.core.playback.loop

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.loop.LoopFrames
import org.junit.Test

/**
 * The three time lines a loop touches: the beat grid's (the analysis decodes with the player's gapless trimming, so
 * its zero is the first frame after the encoder delay), the decoder's presentation times the sink sees (untrimmed, and
 * offset by the renderer's stream offset, about 1e12 us) and the playback clock's (what the player reports, with the
 * sink's trimming taken off only while the stream plays from its start).
 */
class StreamTimeBaseTest {

    private val rate = 44_100

    /** Renderer time starts at 1e12 us: 4.41e10 frames at 44.1 kHz. */
    private val offset = 44_100_000_000L

    /** A LAME MP3: 576 frames of encoder delay, trimmed from the start of the stream. */
    private val fromStart = StreamTimeBase(rate, encoderDelayFrames = 576, streamOffsetFrames = offset, trimmedFrames = 576)

    /** The same file after a seek: nothing is trimmed any more. */
    private val afterSeek = StreamTimeBase(rate, encoderDelayFrames = 576, streamOffsetFrames = offset, trimmedFrames = 0)

    @Test
    fun `a grid time is a decoder frame after the encoder delay and the stream offset`() {
        assertThat(fromStart.gridMsToFrame(1_000.0)).isEqualTo(offset + 576 + 44_100)
        assertThat(fromStart.gridMsToFrame(0.0)).isEqualTo(offset + 576)
    }

    @Test
    fun `grid frames and times convert both ways`() {
        for (ms in listOf(0.0, 1.0, 1_234.5678, 187_000.0)) {
            val frame = fromStart.gridMsToFrame(ms)
            assertThat(fromStart.frameToGridMs(frame)).isWithin(1_000.0 / rate).of(ms)
        }
    }

    @Test
    fun `clock and grid time agree while the stream plays from its start`() {
        val frame = fromStart.gridMsToFrame(5_000.0)
        assertThat(fromStart.frameToClockMs(frame)).isWithin(1e-6).of(5_000.0)
    }

    @Test
    fun `after a seek the clock runs one encoder delay ahead of the grid, as the player reports it`() {
        val frame = afterSeek.gridMsToFrame(5_000.0)
        assertThat(afterSeek.frameToClockMs(frame)).isWithin(1e-6).of(5_000.0 + 576 * 1_000.0 / rate)
    }

    @Test
    fun `clock times and frames convert both ways in either state`() {
        for (base in listOf(fromStart, afterSeek)) {
            for (ms in listOf(0.0, 10.5, 61_234.0)) {
                val frame = base.clockMsToFrame(ms)
                assertThat(base.frameToClockMs(frame)).isWithin(1_000.0 / rate).of(ms)
            }
        }
    }

    @Test
    fun `a loop from the grid is two decoder frames`() {
        assertThat(fromStart.gridFrames(1_000.0, 3_000.0))
            .isEqualTo(LoopFrames(offset + 576 + 44_100, offset + 576 + 3 * 44_100))
    }

    @Test
    fun `a stream without encoder delay or offset maps frames to milliseconds plainly`() {
        val plain = StreamTimeBase(48_000, encoderDelayFrames = 0, streamOffsetFrames = 0, trimmedFrames = 0)
        assertThat(plain.gridMsToFrame(250.0)).isEqualTo(12_000)
        assertThat(plain.frameToClockMs(24_000)).isWithin(1e-9).of(500.0)
    }
}
