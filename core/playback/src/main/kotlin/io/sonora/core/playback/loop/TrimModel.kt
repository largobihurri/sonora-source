package io.sonora.core.playback.loop

/**
 * The sink's model of the trimming `DefaultAudioSink` does below it, which decides how the player's position relates to
 * the decoder's frames (see [StreamTimeBase]): a stream configured with an encoder delay has that many frames dropped
 * from its start, and the position counts from the first frame kept; after a seek (a flush once audio has played) the
 * trimming processor is not reconfigured and nothing is dropped.
 *
 * This is a model of Media3 1.11's behaviour, read from its design and not measured on a device; it only decides where
 * a loop is drawn (within one encoder delay), never what is heard.
 */
internal class TrimModel {
    /** The configured stream's encoder delay, in frames. */
    var delayFrames: Long = 0L
        private set

    /** The frames the sink currently takes off the start of the position. */
    var trimmedFrames: Long = 0L
        private set

    private var audioSinceConfigure = false

    /** A stream is configured with [encoderDelayFrames] (negative: the format says nothing, so none). */
    fun configured(encoderDelayFrames: Long) {
        delayFrames = encoderDelayFrames.coerceAtLeast(0L)
        trimmedFrames = delayFrames
        audioSinceConfigure = false
    }

    /** A buffer of audio was handled. */
    fun audioHandled() {
        audioSinceConfigure = true
    }

    /** The sink was flushed: once audio has played, that is a seek and the trimming is off. */
    fun flushed() {
        if (audioSinceConfigure) trimmedFrames = 0L
    }

    /** Nothing is configured. */
    fun reset() {
        delayFrames = 0L
        trimmedFrames = 0L
        audioSinceConfigure = false
    }
}
