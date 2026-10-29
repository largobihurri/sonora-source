package io.sonora.core.beatmodel

import io.sonora.core.djmodel.BeatModelDescriptor
import io.sonora.core.djmodel.MelSpectrogram
import java.nio.FloatBuffer
import kotlin.math.abs

/** The tensors of the model, shaped and checked in pure Kotlin (no ONNX Runtime types), so the JVM tests cover them. */
internal object BeatTensorShaping {

    /**
     * Rejects a chunk the model cannot take: wrong bin count or frame rate, no frames, or more than the descriptor allows.
     *
     * @throws IllegalArgumentException
     */
    fun checkChunk(chunk: MelSpectrogram, descriptor: BeatModelDescriptor) {
        require(chunk.binCount == descriptor.melBinCount) {
            "the chunk has ${chunk.binCount} bins, the model takes ${descriptor.melBinCount}"
        }
        require(abs(chunk.framesPerSecond - descriptor.framesPerSecond) < 1e-9) {
            "the chunk runs at ${chunk.framesPerSecond} frames per second, the model at ${descriptor.framesPerSecond}"
        }
        require(chunk.frameCount in 1..descriptor.chunkFrames) {
            "the chunk has ${chunk.frameCount} frames, the model takes 1 to ${descriptor.chunkFrames}"
        }
    }

    /** `[1, frames, bins]`: one chunk is one batch. */
    fun inputShape(frames: Int, bins: Int): LongArray = longArrayOf(1L, frames.toLong(), bins.toLong())

    /**
     * Writes [chunk] frame-major into [target] (cleared first, flipped after: `remaining()` is `frames * bins`), through
     * [staging], which holds at least that many values. Returns the frame count.
     *
     * @throws IllegalArgumentException if a value is not finite.
     */
    fun writeInput(chunk: MelSpectrogram, staging: FloatArray, target: FloatBuffer): Int {
        val frames = chunk.frameCount
        val count = frames * chunk.binCount
        chunk.copyFrames(0, frames, staging)
        for (i in 0 until count) {
            require(staging[i].isFinite()) { "the chunk holds ${staging[i]} at frame ${i / chunk.binCount}, bin ${i % chunk.binCount}" }
        }
        target.clear()
        target.put(staging, 0, count)
        target.flip()
        return frames
    }

    /**
     * The values of the output called [name], which must have shape `[1, frames]` ([shape]) and finite values.
     *
     * @throws IllegalStateException if the model answered anything else.
     */
    fun readOutput(name: String, shape: LongArray, values: FloatBuffer, frames: Int): FloatArray {
        check(shape.size == 2 && shape[0] == 1L && shape[1] == frames.toLong()) {
            "output '$name' has shape ${shape.contentToString()}, expected [1, $frames]"
        }
        check(values.remaining() == frames) { "output '$name' holds ${values.remaining()} values, expected $frames" }
        val out = FloatArray(frames)
        values.duplicate().get(out)
        for (i in out.indices) check(out[i].isFinite()) { "output '$name' is ${out[i]} at frame $i" }
        return out
    }
}
