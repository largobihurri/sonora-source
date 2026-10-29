package io.sonora.core.djengine.key

/**
 * Pitch-class energy over time at [BINS_PER_FRAME] (36: three bins per semitone) bins per frame, frame-major: frame
 * `f`, bin `b` is `values[f * BINS_PER_FRAME + b]`. Produced by the chroma sink and consumed by the key matcher. The
 * array is not copied: the producer must not change it afterwards.
 */
class ChromaFrames(private val values: FloatArray, val framesPerSecond: Double) {
    init {
        require(values.size % BINS_PER_FRAME == 0) {
            "values (${values.size}) must be a whole number of $BINS_PER_FRAME-bin frames"
        }
        require(framesPerSecond.isFinite() && framesPerSecond > 0) { "framesPerSecond must be positive: $framesPerSecond" }
    }

    val frameCount: Int get() = values.size / BINS_PER_FRAME

    operator fun get(frame: Int, bin: Int): Float {
        require(bin in 0 until BINS_PER_FRAME) { "bin $bin of $BINS_PER_FRAME" }
        return values[frame * BINS_PER_FRAME + bin]
    }

    companion object {
        const val BINS_PER_FRAME: Int = 36
    }
}
