package io.sonora.core.playback.loop

import io.sonora.core.djmodel.loop.LoopFrames
import io.sonora.core.djmodel.loop.LoopTime

/**
 * The three time lines a DJ loop touches, for one stream:
 *
 * - **The grid's** (the beat grid and the cues, "grid time"). The analysis decodes with the same gapless trimming the
 *   player applies, so its zero is the first frame after the stream's encoder delay (LAME header, AAC priming).
 * - **The decoder's** (the frames the loop engine counts: presentation time x sample rate). The sink sees the decoder's
 *   PCM before `DefaultAudioSink` trims it, so its first frame is the encoder delay *before* the grid's zero; and the
 *   renderer adds its stream offset (about 1e12 us for the first period, more for each later one) to every time.
 * - **The clock's** (what the player reports and the DJ screen draws against). It is the renderer time minus the
 *   stream offset, and minus the frames the sink has trimmed from the start of what it plays: the encoder delay while
 *   the stream plays from its beginning, nothing after a seek (the trimming processor only trims after a configuration,
 *   not after a flush). So the clock equals grid time at the start of a stream and runs one encoder delay ahead of it
 *   after a seek, which is how the screen already draws the grid; loop times shown to the user are in the clock's time
 *   so they sit where the playhead is.
 *
 * Beat loops are computed on the grid and played on the decoder's frames, so they land on the drawn beats whatever the
 * delay. [trimmedFrames] is the sink's model of its own trimming, not a measurement: it is wrong, by at most the
 * encoder delay (13 ms for LAME, 48 ms for AAC), if the sink trims otherwise than described, and that is only seen in
 * where the wrap is drawn, not in the audio.
 *
 * @property encoderDelayFrames the frames the stream's format says to trim from its start (0 when it says nothing).
 * @property streamOffsetFrames the renderer's offset of the stream's period, in frames.
 * @property trimmedFrames the frames the sink takes off the start of the stream's position, see above.
 */
internal class StreamTimeBase(
    val sampleRate: Int,
    val encoderDelayFrames: Long,
    val streamOffsetFrames: Long,
    val trimmedFrames: Long,
) {
    /** The decoder frame that plays the track at [gridMs]. */
    fun gridMsToFrame(gridMs: Double): Long =
        LoopTime.msToFrames(gridMs, sampleRate) + encoderDelayFrames + streamOffsetFrames

    /** The grid time of the decoder [frame]. */
    fun frameToGridMs(frame: Long): Double =
        LoopTime.framesToMs(frame - encoderDelayFrames - streamOffsetFrames, sampleRate)

    /** The clock time at which the decoder [frame] is heard. */
    fun frameToClockMs(frame: Long): Double =
        LoopTime.framesToMs(frame - streamOffsetFrames - trimmedFrames, sampleRate)

    /** The decoder frame heard at the clock time [clockMs]. */
    fun clockMsToFrame(clockMs: Double): Long =
        LoopTime.msToFrames(clockMs, sampleRate) + streamOffsetFrames + trimmedFrames

    /** The loop `[inMs, outMs)` of the grid as decoder frames. */
    fun gridFrames(inMs: Double, outMs: Double): LoopFrames = LoopFrames(gridMsToFrame(inMs), gridMsToFrame(outMs))
}
