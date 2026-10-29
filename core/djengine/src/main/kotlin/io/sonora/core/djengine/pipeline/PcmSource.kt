package io.sonora.core.djengine.pipeline

/**
 * The decoded audio of one track, pulled block by block by [DjAnalysisPipeline]: interleaved float samples (-1..1) of
 * [channelCount] channels at [sampleRateHz]. The Android decoder adapts to it; tests feed arrays. The caller opens
 * and closes it; the pipeline only reads.
 */
interface PcmSource {

    /** Sample rate in Hz of the decoded audio. */
    val sampleRateHz: Int

    /** Number of interleaved channels (1 or more). */
    val channelCount: Int

    /** Frames the whole track holds, when known (it drives the progress), or `null`. */
    val totalFrames: Long?

    /**
     * Blocks until it can write up to `buffer.size / channelCount` whole frames of interleaved samples into [buffer]
     * from index 0, and returns how many frames it wrote, or -1 at the end of the audio. A return of 0 means nothing
     * yet: the pipeline checks for cancellation and asks again.
     */
    fun read(buffer: FloatArray): Int

    /**
     * Goes back to the first frame for a second decode and returns `true`, or returns `false` when this source cannot
     * (the default). The pipeline asks only in the one case documented on [FallbackPolicy.ONLY_WITHOUT_MODEL].
     */
    fun restart(): Boolean = false
}
