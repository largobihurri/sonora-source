package io.sonora.core.djmodel

/**
 * How Sonora splits a spectrogram into chunks for Beat This! small (`small0`, the D1.18 ONNX export): the one
 * definition the app's model ([BeatModelDescriptor.chunkFrames] and [BeatModelDescriptor.borderFrames] of
 * `core:beatmodel`) and the host calibration (`tools/calibration`) share.
 *
 * Upstream Beat This! runs chunks of 1500 frames (30 s) with a 6-frame border. The D1.20 calibration found that this
 * export loses accuracy as chunks grow: on a synthetic 128 BPM drum loop the beat F-measure (+-70 ms) was 0.255 with
 * 1500-frame chunks, 0.481 at 750, 0.81 at 400, 0.94 at 300 and 0.85 at 256; on five real tracks the raw model F rose
 * from 0.353 (1500/6) to 0.577 (300/24). Every calibrated default of the pipeline was measured with 300/24. The
 * defect is in the export (the trace probably fixed a sequence length); re-exporting the model and checking it against
 * PyTorch on real music is a later task, after which this may go back to the upstream chunking.
 *
 * Shorter chunks are also cheaper: the time attention is quadratic in the chunk length (unit D1.26 measured the host
 * peak memory and time per chunk).
 */
object BeatThisChunking {

    /** Frames per chunk: 6 s at 50 frames per second. */
    const val CHUNK_FRAMES: Int = 300

    /** Frames dropped at each end of a chunk's output (0.48 s); consecutive chunks overlap by twice this. */
    const val BORDER_FRAMES: Int = 24
}
