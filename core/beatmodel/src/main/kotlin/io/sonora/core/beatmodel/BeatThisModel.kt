package io.sonora.core.beatmodel

import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.BeatModelDescriptor
import io.sonora.core.djmodel.BeatThisChunking

/** Where a model file lives in the app's assets and what it must hash to. */
data class BeatModelFile(val assetPath: String, val sha256: String, val sizeBytes: Long) {
    init {
        require(assetPath.isNotBlank()) { "assetPath must not be blank" }
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "sha256 must be 64 lowercase hex digits: $sha256" }
        require(sizeBytes > 0) { "sizeBytes must be positive: $sizeBytes" }
    }
}

/**
 * Beat This! `small0` (CPJKU, ISMIR 2024; code and weights MIT) as exported to ONNX and pinned by
 * `tools/beatthis/model.json`, which is the source of truth for [SHA256] and [SIZE_BYTES] (a test compares them).
 * The model takes a log-mel spectrogram `spectrogram` of shape `[batch, time, 128]` and answers the beat and downbeat
 * LOGITS `beat` and `downbeat` of shape `[batch, time]`, 50 frames per second; `tools/beatthis/README.md` has the
 * front end and the chunking the callers must follow.
 */
object BeatThisModel {

    const val ID = "beatthis-small0"
    const val RUNTIME = "onnxruntime-cpu"

    /** Path in the app's assets, written there by the `fetchBeatModel` Gradle task. */
    const val ASSET_PATH = "beatmodel/small0.onnx"
    const val SHA256 = "c462b064a4033050ca6a5354bf866ff4610ab1fd8f1dec1e494b20b22d870aea"
    const val SIZE_BYTES = 10_401_044L

    const val SAMPLE_RATE_HZ = 22_050
    const val MEL_BIN_COUNT = 128
    const val FRAMES_PER_SECOND = 50.0

    /**
     * The longest input in frames and the border the engine drops at each end: [BeatThisChunking] (300 and 24), not
     * the upstream 1500 and 6, because this export loses accuracy with long chunks (D1.20). The time attention is
     * quadratic, so short chunks are also far lighter: on the host (D1.26) a 300-frame chunk takes about 95 ms and the
     * process peaks at 157 MB (128 MB of it is the JVM, the runtime and the loaded model), against 0.81 s and 810 MB for
     * one of 1500 frames; a 5-minute track needs 60 short chunks instead of 11 long ones, about 5.7 s instead of 8.9 s.
     */
    const val CHUNK_FRAMES = BeatThisChunking.CHUNK_FRAMES
    const val BORDER_FRAMES = BeatThisChunking.BORDER_FRAMES

    const val INPUT_NAME = "spectrogram"
    const val BEAT_OUTPUT = "beat"
    const val DOWNBEAT_OUTPUT = "downbeat"

    val FILE = BeatModelFile(ASSET_PATH, SHA256, SIZE_BYTES)

    /**
     * The descriptor of this model on the CPU. The version names the weights (the first eight hex digits of their
     * sha256), the runtime and the chunking (`c<chunk>b<border>`), so an analysis made with another file, another
     * runtime or another chunking (which changes the activations) is told apart.
     */
    fun descriptor(sha256: String = SHA256): BeatModelDescriptor = BeatModelDescriptor(
        id = ID,
        version = "${sha256.take(8)}-$RUNTIME-c${CHUNK_FRAMES}b$BORDER_FRAMES",
        sampleRateHz = SAMPLE_RATE_HZ,
        melBinCount = MEL_BIN_COUNT,
        framesPerSecond = FRAMES_PER_SECOND,
        chunkFrames = CHUNK_FRAMES,
        borderFrames = BORDER_FRAMES,
        accelerator = Accelerator.CPU,
    )
}
