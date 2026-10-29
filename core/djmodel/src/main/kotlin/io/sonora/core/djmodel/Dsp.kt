package io.sonora.core.djmodel

/**
 * Receives the mono audio of a track chunk by chunk and turns it into a result; one sink per analysis stage, all fed
 * from a single decode. Not thread-safe: a sink is driven by one thread.
 */
interface MonoSink<out R> {

    /** Consumes `samples[offset until offset + count]` (mono, -1..1). */
    fun accept(samples: FloatArray, offset: Int, count: Int)

    /** Called once after the last chunk; returns the result. The sink is not used afterwards. */
    fun finish(): R
}

/** Creates the sinks of one stage. The pipeline resamples the decode once to [sampleRateHz] for it. */
interface MonoSinkFactory<out R> {

    /** Sample rate in Hz of the mono audio the sink wants. */
    val sampleRateHz: Int

    fun create(): MonoSink<R>
}

/**
 * Log-mel spectrogram, frame-major: frame `f`, bin `b` is `values[f * binCount + b]`. The array is not copied (it can
 * hold millions of values): the owner must not change it after construction.
 */
class MelSpectrogram(
    val binCount: Int,
    val framesPerSecond: Double,
    private val values: FloatArray,
) {
    init {
        require(binCount > 0) { "binCount must be positive: $binCount" }
        require(framesPerSecond.isFinite() && framesPerSecond > 0) { "framesPerSecond must be positive: $framesPerSecond" }
        require(values.size % binCount == 0) { "values (${values.size}) must be a whole number of $binCount-bin frames" }
    }

    val frameCount: Int get() = values.size / binCount

    operator fun get(frame: Int, bin: Int): Float {
        require(bin in 0 until binCount) { "bin $bin of $binCount" }
        return values[frame * binCount + bin]
    }

    /** Copies `frames` frames from [fromFrame] into [destination] at [destinationOffset] (frame-major). */
    fun copyFrames(fromFrame: Int, frames: Int, destination: FloatArray, destinationOffset: Int = 0) {
        require(fromFrame >= 0 && frames >= 0 && fromFrame + frames <= frameCount) {
            "frames $fromFrame..${fromFrame + frames} outside 0..$frameCount"
        }
        values.copyInto(destination, destinationOffset, fromFrame * binCount, (fromFrame + frames) * binCount)
    }
}

/** What the model outputs for one chunk: a beat and a downbeat logit per frame (equal lengths). */
class ChunkLogits(val beat: FloatArray, val downbeat: FloatArray) {
    init {
        require(beat.size == downbeat.size) { "beat (${beat.size}) and downbeat (${downbeat.size}) must have the same length" }
    }

    val frameCount: Int get() = beat.size
}

/** Beat and downbeat LOGITS (raw model outputs, not probabilities: 0 is the decision threshold) for a whole track, one value per frame at [framesPerSecond] (equal lengths, copied). */
class BeatActivations(beat: FloatArray, downbeat: FloatArray, val framesPerSecond: Double) {
    private val beatValues = beat.copyOf()
    private val downbeatValues = downbeat.copyOf()

    init {
        require(beatValues.size == downbeatValues.size) {
            "beat (${beatValues.size}) and downbeat (${downbeatValues.size}) must have the same length"
        }
        require(framesPerSecond.isFinite() && framesPerSecond > 0) { "framesPerSecond must be positive: $framesPerSecond" }
    }

    val frameCount: Int get() = beatValues.size

    fun beat(): FloatArray = beatValues.copyOf()

    fun downbeat(): FloatArray = downbeatValues.copyOf()
}

/** Low-band onset strength over time, [framesPerSecond] values per second (copied). */
class OnsetEnvelope(values: FloatArray, val framesPerSecond: Double) {
    private val data = values.copyOf()

    init {
        require(framesPerSecond.isFinite() && framesPerSecond > 0) { "framesPerSecond must be positive: $framesPerSecond" }
    }

    val frameCount: Int get() = data.size

    fun values(): FloatArray = data.copyOf()
}

/**
 * Beat and downbeat times picked from the activations, in ms from the start of the track, strictly increasing
 * (sub-frame refined); every downbeat is also a beat time (copied).
 *
 * [downbeatProbabilities], when given, holds one value per beat in 0..1 (how likely that beat starts a bar, `NaN` when
 * unknown); by default every beat is unknown.
 */
class BeatCandidates(beatsMs: DoubleArray, downbeatsMs: DoubleArray, downbeatProbabilities: DoubleArray? = null) {
    private val beatTimes = beatsMs.copyOf()
    private val downbeatTimes = downbeatsMs.copyOf()
    private val probabilities = downbeatProbabilities?.copyOf() ?: DoubleArray(beatsMs.size) { Double.NaN }

    init {
        require(isIncreasing(beatTimes)) { "beat times must be finite and strictly increasing" }
        require(isIncreasing(downbeatTimes)) { "downbeat times must be finite and strictly increasing" }
        require(probabilities.size == beatTimes.size) {
            "downbeatProbabilities (${probabilities.size}) must have one value per beat (${beatTimes.size})"
        }
        require(probabilities.all { it.isNaN() || it in 0.0..1.0 }) { "downbeat probabilities must be NaN or within 0..1" }
    }

    val beatCount: Int get() = beatTimes.size

    fun beatsMs(): DoubleArray = beatTimes.copyOf()

    fun downbeatsMs(): DoubleArray = downbeatTimes.copyOf()

    /** One value per beat: the probability that it starts a bar, or `NaN` when unknown. */
    fun downbeatProbabilities(): DoubleArray = probabilities.copyOf()

    private fun isIncreasing(a: DoubleArray): Boolean =
        a.all { it.isFinite() } && (1 until a.size).all { a[it] > a[it - 1] }
}
