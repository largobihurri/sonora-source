package io.sonora.tools.calibration.runner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.BeatModelDescriptor
import io.sonora.core.djmodel.BeatThisChunking
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Beat This! small (`tools/beatthis`, D1.18: `small0.onnx`, input `spectrogram [1, T, 128]`, outputs `beat` and
 * `downbeat [1, T]` logits at 50 frames per second) on the host CPU through the desktop ONNX Runtime, for the
 * calibration only; D1.21 built the Android adapter. [DESCRIPTOR] chunks like the app: [BeatThisChunking], 300-frame
 * chunks with a 24-frame border (D1.26; the D1.20 runs passed them as options), id `beatthis-small/1`.
 *
 * One ORT session is opened for the whole calibration run ([open]) and shared by every track: [create] hands the
 * pipeline a view whose `close` does nothing, so the per-track INFERENCE time is inference alone. Threads: intra-op
 * [intraOpThreads] (1 or 2 on this machine, which overheats), inter-op 1, sequential execution.
 */
class HostBeatThisModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    /** [DESCRIPTOR], or a copy with other chunking for the calibration's chunk-length experiments. */
    val descriptor: BeatModelDescriptor,
) : BeatActivationModelFactory, AutoCloseable {

    override suspend fun create(preferred: Accelerator): BeatActivationModel = object : BeatActivationModel {
        override val descriptor: BeatModelDescriptor = this@HostBeatThisModel.descriptor

        override fun infer(chunk: MelSpectrogram): ChunkLogits = run(chunk)

        override fun close() = Unit
    }

    /** One chunk through the session. */
    fun run(chunk: MelSpectrogram): ChunkLogits {
        require(chunk.binCount == DESCRIPTOR.melBinCount) { "The model takes ${DESCRIPTOR.melBinCount} bins, got ${chunk.binCount}" }
        val frames = chunk.frameCount
        require(frames in 1..descriptor.chunkFrames) { "Chunk of $frames frames, the model takes 1..${descriptor.chunkFrames}" }
        val input = FloatArray(frames * chunk.binCount)
        chunk.copyFrames(0, frames, input)
        val shape = longArrayOf(1, frames.toLong(), chunk.binCount.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(INPUT to tensor)).use { result ->
                val beat = output(result, BEAT_OUTPUT)
                val downbeat = output(result, DOWNBEAT_OUTPUT)
                check(beat.size == frames && downbeat.size == frames) {
                    "The model answered ${beat.size}/${downbeat.size} frames for $frames"
                }
                return ChunkLogits(beat, downbeat)
            }
        }
    }

    override fun close() = session.close()

    private fun output(result: OrtSession.Result, name: String): FloatArray {
        val value = result.get(name).orElseThrow { IllegalStateException("The model has no output '$name'") }.value
        @Suppress("UNCHECKED_CAST")
        return (value as Array<FloatArray>)[0]
    }

    companion object {
        const val INPUT: String = "spectrogram"
        const val BEAT_OUTPUT: String = "beat"
        const val DOWNBEAT_OUTPUT: String = "downbeat"

        /** The host model's identity (D1.16: id `beatthis-small`, version `1`) with the app's chunking. */
        val DESCRIPTOR: BeatModelDescriptor = BeatModelDescriptor(
            id = "beatthis-small",
            version = "1",
            sampleRateHz = 22_050,
            melBinCount = 128,
            framesPerSecond = 50.0,
            chunkFrames = BeatThisChunking.CHUNK_FRAMES,
            borderFrames = BeatThisChunking.BORDER_FRAMES,
            accelerator = Accelerator.CPU,
        )

        /**
         * Opens [modelFile] with [intraOpThreads] intra-op threads. [chunkFrames] and [borderFrames] change the chunking
         * the pipeline uses (the descriptor's id and version stay): the calibration found the exported model's output
         * to depend on the chunk length, see the D1.20 report.
         */
        fun open(
            modelFile: Path,
            intraOpThreads: Int,
            chunkFrames: Int = DESCRIPTOR.chunkFrames,
            borderFrames: Int = DESCRIPTOR.borderFrames,
        ): HostBeatThisModel {
            require(Files.isRegularFile(modelFile)) { "No model at $modelFile (run tools/beatthis/fetch_model.py)" }
            require(intraOpThreads >= 1) { "intraOpThreads must be at least 1: $intraOpThreads" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(intraOpThreads)
                setInterOpNumThreads(1)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val descriptor = DESCRIPTOR.copy(chunkFrames = chunkFrames, borderFrames = borderFrames)
            return HostBeatThisModel(environment, environment.createSession(modelFile.toString(), options), descriptor)
        }
    }
}
