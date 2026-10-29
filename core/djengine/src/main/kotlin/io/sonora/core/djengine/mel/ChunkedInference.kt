package io.sonora.core.djengine.mel

import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivations
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import kotlin.math.abs

/**
 * Runs a beat model over a whole log-mel spectrogram in overlapping chunks and stitches the outputs together, as
 * `split_predict_aggregate` of Beat This! (CPJKU/beat_this, MIT, `beat_this/inference.py`; the parameters are
 * `tools/beatthis/README.md`) does with `overlap_mode="keep_first"` and `avoid_short_end=True`. The chunk size and the
 * border come from the model's descriptor: upstream Beat This! uses 1500 and 6 frames, the app runs its export with
 * `BeatThisChunking` (300 and 24, D1.20/D1.26).
 *
 * With `T` frames, `chunk` and `border`:
 *
 * - Chunk starts are `-border, -border + (chunk - 2 border), ...` while below `T - border` (so the first chunk starts
 *   `border` frames before the piece). When `T` is longer than `chunk - 2 border` the LAST start is moved left to
 *   `T - (chunk - border)`, so the last chunk is full and never a short tail.
 * - A chunk is `mel[max(start, 0) until min(start + chunk, T)]`, padded with zero frames (zeros in the log-mel domain)
 *   before the first frame when `start < 0` and, after the last frame, by `min(border, start + chunk - T)` frames
 *   when that is positive. A piece of at most `chunk - 2 border` frames is therefore one chunk of at most `chunk`
 *   frames.
 * - Each chunk goes to the model on its own. The model's first and last `border` outputs are dropped (they saw
 *   padding or too little context); chunk `i` then owns frames `start + border until start + length - border`.
 * - The result is filled from the last chunk to the first, so where owned ranges overlap the EARLIER chunk wins
 *   ("keep first"). A frame no chunk owns cannot occur: every frame is inside some owned range. The reference
 *   starts the prediction at -1000; here that value is only ever overwritten.
 *
 * The outputs are logits; no sigmoid is applied.
 */
object ChunkedInference {

    private const val UNSET_LOGIT = -1000f

    /**
     * Runs [model] over [mel] and returns the beat and downbeat logits, one value per frame of [mel] at the model's
     * frame rate.
     *
     * @throws IllegalArgumentException if [mel] does not have the model's bin count and frame rate.
     * @throws IllegalStateException if the model answers a chunk with a different number of frames.
     */
    fun run(model: BeatActivationModel, mel: MelSpectrogram): BeatActivations {
        val descriptor = model.descriptor
        require(mel.binCount == descriptor.melBinCount) {
            "mel has ${mel.binCount} bins, the model takes ${descriptor.melBinCount}"
        }
        require(abs(mel.framesPerSecond - descriptor.framesPerSecond) < 1e-9) {
            "mel runs at ${mel.framesPerSecond} frames per second, the model at ${descriptor.framesPerSecond}"
        }
        val frames = mel.frameCount
        if (frames == 0) return BeatActivations(FloatArray(0), FloatArray(0), descriptor.framesPerSecond)

        val chunkSize = descriptor.chunkFrames
        val border = descriptor.borderFrames
        val starts = chunkStarts(frames, chunkSize, border)

        val outputs = ArrayList<ChunkLogits>(starts.size)
        for (start in starts) outputs += model.infer(buildChunk(mel, start, chunkSize, border)).also {
            val expected = chunkLength(frames, start, chunkSize, border)
            check(it.frameCount == expected) { "the model answered ${it.frameCount} frames for a chunk of $expected" }
        }

        val beat = FloatArray(frames) { UNSET_LOGIT }
        val downbeat = FloatArray(frames) { UNSET_LOGIT }
        for (i in starts.indices.reversed()) {
            val start = starts[i]
            val out = outputs[i]
            val from = maxOf(start + border, 0)
            val to = minOf(start + out.frameCount - border, frames)
            if (to > from) {
                out.beat.copyInto(beat, from, from - start, to - start)
                out.downbeat.copyInto(downbeat, from, from - start, to - start)
            }
        }
        return BeatActivations(beat, downbeat, descriptor.framesPerSecond)
    }

    /** The first frame (possibly negative) of every chunk of a piece of [frames] frames. */
    internal fun chunkStarts(frames: Int, chunkSize: Int, border: Int): IntArray {
        if (frames <= 0) return IntArray(0)
        val step = chunkSize - 2 * border
        val starts = ArrayList<Int>()
        var start = -border
        while (start < frames - border) {
            starts += start
            start += step
        }
        // avoid_short_end: when the piece is longer than one step, the last chunk is pulled left to be full.
        if (frames > step) starts[starts.size - 1] = frames - (chunkSize - border)
        return starts.toIntArray()
    }

    /** Frames of the chunk starting at [start], padding included. */
    private fun chunkLength(frames: Int, start: Int, chunkSize: Int, border: Int): Int {
        val padLeft = maxOf(-start, 0)
        val padRight = maxOf(minOf(border, start + chunkSize - frames), 0)
        return minOf(start + chunkSize, frames) - maxOf(start, 0) + padLeft + padRight
    }

    private fun buildChunk(mel: MelSpectrogram, start: Int, chunkSize: Int, border: Int): MelSpectrogram {
        val frames = mel.frameCount
        val bins = mel.binCount
        val padLeft = maxOf(-start, 0)
        val from = maxOf(start, 0)
        val count = minOf(start + chunkSize, frames) - from
        val values = FloatArray(chunkLength(frames, start, chunkSize, border) * bins)
        mel.copyFrames(from, count, values, padLeft * bins)
        return MelSpectrogram(bins, mel.framesPerSecond, values)
    }
}
