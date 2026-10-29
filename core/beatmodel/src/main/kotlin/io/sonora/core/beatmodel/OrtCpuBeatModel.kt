package io.sonora.core.beatmodel

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatModelDescriptor
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Beat This! `small0` on ONNX Runtime's CPU provider (the full Android build: the graph needs Erf, Sin, Cos and
 * ReduceL2, which a reduced-ops build lacks). One [OrtEnvironment] and one [OrtSession] per instance, both released by
 * [close].
 *
 * Each [infer] call is one batch of one chunk. The input travels in a direct [FloatBuffer] allocated once for the
 * longest chunk (the session reads it without a copy); [BeatTensorShaping] checks and lays out the tensors.
 *
 * Session settings (see [OrtCpuConfig] for the knobs): intra-op threads 2 by default, one inter-op thread and
 * sequential execution (the graph is a chain, no parallel branches worth a pool); all graph optimisations; idle
 * threads do not spin, so a background analysis does not burn a core while a chunk is being prepared; the CPU arena
 * is on and the memory pattern off (host measurements in `odd/tasks/dj-mode.md`, unit D1.21: arena on and pattern off
 * kept one chunk of 1500 frames at 0.8 GB however many ran, pattern on grew to 1.4 GB, arena off peaked at 1.0 GB; with
 * the app's 300-frame chunks the process peaks at 157 MB, D1.26).
 * Memory is the thing to watch: the time attention is quadratic.
 *
 * Thread-safe in the sense that calls are serialised; the owner closes it, and [infer] after [close] is an error.
 */
class OrtCpuBeatModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    override val descriptor: BeatModelDescriptor,
) : BeatActivationModel {

    private val staging = FloatArray(descriptor.chunkFrames * descriptor.melBinCount)
    private val input: FloatBuffer = ByteBuffer.allocateDirect(staging.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private var open = true

    /**
     * Runs one chunk of `1..descriptor.chunkFrames` frames.
     *
     * @throws IllegalArgumentException if the chunk has another bin count or frame rate, no frame, more than
     *   `chunkFrames` frames, or a value that is not finite.
     * @throws IllegalStateException if the model is closed, the runtime fails, or it answers something unexpected.
     */
    @Synchronized
    override fun infer(chunk: MelSpectrogram): ChunkLogits {
        check(open) { "the beat model is closed" }
        BeatTensorShaping.checkChunk(chunk, descriptor)
        val frames = BeatTensorShaping.writeInput(chunk, staging, input)
        try {
            OnnxTensor.createTensor(environment, input, BeatTensorShaping.inputShape(frames, descriptor.melBinCount)).use { tensor ->
                session.run(mapOf(BeatThisModel.INPUT_NAME to tensor)).use { result ->
                    return ChunkLogits(
                        read(result, BeatThisModel.BEAT_OUTPUT, frames),
                        read(result, BeatThisModel.DOWNBEAT_OUTPUT, frames),
                    )
                }
            }
        } catch (e: OrtException) {
            throw IllegalStateException("the beat model failed on a chunk of $frames frames: ${e.message}", e)
        }
    }

    private fun read(result: OrtSession.Result, name: String, frames: Int): FloatArray {
        val value = result.get(name).orElseThrow { IllegalStateException("the model has no output '$name'") }
        check(value is OnnxTensor) { "output '$name' is not a tensor" }
        return BeatTensorShaping.readOutput(name, value.info.shape, value.floatBuffer, frames)
    }

    /** Releases the session and the environment reference. Idempotent. */
    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        try {
            session.close()
        } finally {
            environment.close()
        }
    }

    companion object {
        private const val ENVIRONMENT_NAME = "sonora-beatmodel"

        /**
         * Opens a session on the bytes of the ONNX file (the caller has verified them: [OrtBeatModelFactory] checks the
         * sha256) and checks that it has the input and the two outputs of [BeatThisModel].
         *
         * @throws IllegalStateException if the model does not have the expected input and outputs.
         * @throws OrtException if the runtime cannot load the bytes.
         */
        fun create(
            model: ByteArray,
            descriptor: BeatModelDescriptor = BeatThisModel.descriptor(),
            config: OrtCpuConfig = OrtCpuConfig(),
        ): OrtCpuBeatModel {
            val environment = OrtEnvironment.getEnvironment(ENVIRONMENT_NAME)
            try {
                val session = OrtSession.SessionOptions().use { options ->
                    configure(options, config)
                    environment.createSession(model, options)
                }
                try {
                    check(BeatThisModel.INPUT_NAME in session.inputNames) {
                        "the model has inputs ${session.inputNames}, expected '${BeatThisModel.INPUT_NAME}'"
                    }
                    check(session.outputNames.containsAll(listOf(BeatThisModel.BEAT_OUTPUT, BeatThisModel.DOWNBEAT_OUTPUT))) {
                        "the model has outputs ${session.outputNames}, expected '${BeatThisModel.BEAT_OUTPUT}' and '${BeatThisModel.DOWNBEAT_OUTPUT}'"
                    }
                    return OrtCpuBeatModel(environment, session, descriptor)
                } catch (e: Throwable) {
                    session.close()
                    throw e
                }
            } catch (e: Throwable) {
                environment.close()
                throw e
            }
        }

        private fun configure(options: OrtSession.SessionOptions, config: OrtCpuConfig) {
            options.setIntraOpNumThreads(config.intraOpThreads)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setCPUArenaAllocator(config.cpuArena)
            options.setMemoryPatternOptimization(config.memoryPattern)
            options.addConfigEntry("session.intra_op.allow_spinning", if (config.allowSpinning) "1" else "0")
        }
    }
}
