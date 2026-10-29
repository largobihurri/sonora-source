package io.sonora.core.djmodel

/** Where a model runs. */
enum class Accelerator { CPU, GPU, NPU }

/**
 * Describes a beat model and the frames it takes: [melBinCount] bins at [framesPerSecond], at most [chunkFrames] frames
 * per call, whose outer [borderFrames] on each side the engine discards when it stitches chunks together.
 */
data class BeatModelDescriptor(
    val id: String,
    val version: String,
    val sampleRateHz: Int,
    val melBinCount: Int,
    val framesPerSecond: Double,
    val chunkFrames: Int,
    val borderFrames: Int,
    val accelerator: Accelerator,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank()) { "A model needs an id and a version" }
        require(sampleRateHz > 0 && melBinCount > 0 && chunkFrames > 0) { "Model dimensions must be positive" }
        require(framesPerSecond.isFinite() && framesPerSecond > 0) { "framesPerSecond must be positive: $framesPerSecond" }
        require(borderFrames >= 0 && 2 * borderFrames < chunkFrames) { "borderFrames $borderFrames does not fit chunkFrames $chunkFrames" }
    }
}

/** A loaded beat and downbeat activation model. Not thread-safe; the owner closes it. */
interface BeatActivationModel : AutoCloseable {

    val descriptor: BeatModelDescriptor

    /** Runs one chunk of at most `descriptor.chunkFrames` frames of `descriptor.melBinCount` bins. */
    fun infer(chunk: MelSpectrogram): ChunkLogits
}

/** Opens the beat model, or reports that this build or device has none. */
interface BeatActivationModelFactory {

    /** The model on [preferred] (or the best available), or `null` when there is none: the analyser then falls back to qm-dsp. */
    suspend fun create(preferred: Accelerator = Accelerator.CPU): BeatActivationModel?
}
