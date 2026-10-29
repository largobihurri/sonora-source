package io.sonora.tools.calibration.runner

import io.sonora.core.djengine.key.ChromaFrames
import io.sonora.core.djengine.pipeline.DjAnalysisPipeline
import io.sonora.core.djengine.pipeline.FallbackPolicy
import io.sonora.core.djengine.pipeline.GridFit
import io.sonora.core.djengine.pipeline.KeyDetection
import io.sonora.core.djengine.pipeline.PhaseRefinement
import io.sonora.core.djengine.pipeline.StageSinks
import io.sonora.core.djengine.pipeline.beatSourceId
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisTelemetry
import io.sonora.core.djmodel.AnalysisTimings
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import io.sonora.core.djmodel.OnsetEnvelope
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Runs the real [DjAnalysisPipeline] (D1.16, default sinks, grid fit, phase refinement and key detection) on one
 * reference file decoded by ffmpeg, with the Beat This! model on the host, and keeps what each stage saw as a
 * [TrackCapture].
 *
 * The grid stage runs with the pipeline's defaults; the phase-refinement hook only keeps the onset envelope (and leaves
 * the grid alone, as the default pipeline does since D1.20) so the tuning sweep can try the refinement offline.
 *
 * The host has no qm-dsp: the fallback grid is a sink that finds nothing, and the policy is
 * [FallbackPolicy.ONLY_WITHOUT_MODEL], so it is never fed while the model is open; a model failure then needs a second
 * decode that a pipe cannot give, and the track fails instead of silently getting no grid (the run reports it).
 */
class TrackAnalyser(private val model: HostBeatThisModel, private val decoder: FfmpegDecoder) {

    fun analyse(file: Path, path: String, temperatureC: Double): TrackCapture {
        var candidates: BeatCandidates? = null
        var envelope: OnsetEnvelope? = null
        var chroma: ChromaFrames? = null
        var stageMs: Map<AnalysisStage, Long> = emptyMap()
        val onsetSinks = ArrayList<RecordingSink<OnsetEnvelope>>()
        val failures = ArrayList<String>()

        val telemetry = object : AnalysisTelemetry {
            override fun analysed(audioId: AudioId, timings: AnalysisTimings, provenance: AnalysisProvenance) {
                stageMs = timings.stageMs
            }

            override fun failed(stage: AnalysisStage, cause: Throwable) {
                failures += "$stage: $cause"
            }
        }
        val pipeline = DjAnalysisPipeline(
            modelFactory = model,
            fallbackGrid = NO_FALLBACK,
            telemetry = telemetry,
            onset = StageSinks { rate ->
                val inner = DjAnalysisPipeline.DEFAULT_ONSET.forSourceRate(rate)
                object : MonoSinkFactory<OnsetEnvelope> {
                    override val sampleRateHz: Int = inner.sampleRateHz

                    override fun create(): MonoSink<OnsetEnvelope> = RecordingSink(inner.create()).also { onsetSinks += it }
                }
            },
            gridFit = GridFit { found, durationMs ->
                candidates = found
                DjAnalysisPipeline.DEFAULT_GRID_FIT.fit(found, durationMs)
            },
            // Keeps the envelope for the tuning sweep and leaves the grid as the default pipeline does (no refinement).
            phaseRefinement = PhaseRefinement { grid, _ -> grid },
            keyDetection = KeyDetection { frames ->
                chroma = frames
                DjAnalysisPipeline.DEFAULT_KEY_DETECTION.detect(frames)
            },
            fallbackPolicy = FallbackPolicy.ONLY_WITHOUT_MODEL,
        )

        val started = System.nanoTime()
        var format: FfmpegDecoder.Format? = null
        var framesRead = 0L
        val analysis = decoder.open(file).use { decode ->
            format = decode.format
            val result = runBlocking { pipeline.run(decode.pcm, audioIdOf(path)) }
            val exit = decode.awaitExit()
            if (exit != 0) throw IOException("ffmpeg exited with $exit on $path")
            framesRead = decode.pcm.framesRead
            if (framesRead == 0L) throw IOException("ffmpeg decoded nothing from $path")
            result
        }
        val wallMs = (System.nanoTime() - started) / NANOS_PER_MS
        // The pipeline finishes the onset sink only when it has a grid to refine; finish it here otherwise, so a tuned
        // grid fit that finds a grid where the default did not can still be refined.
        envelope = onsetSinks.firstNotNullOfOrNull { it.result } ?: onsetSinks.firstOrNull()?.finish()
        val decoded = checkNotNull(format)

        return TrackCapture(
            path = path,
            format = ReferencePaths.format(path),
            sampleRateHz = decoded.sampleRateHz,
            channelCount = decoded.channelCount,
            // The exact length the pipeline fitted with (DjAnalysis rounds it to whole milliseconds).
            durationMs = framesRead * MS_PER_SECOND / decoded.sampleRateHz,
            candidates = candidates,
            envelope = envelope,
            chroma = chroma,
            waveform = analysis.waveform,
            pipelineGrid = analysis.grid,
            pipelineKey = analysis.key,
            beatSourceId = analysis.provenance.beatSourceId,
            stageMs = stageMs,
            wallMs = wallMs,
            temperatureC = temperatureC,
        ).also { if (failures.isNotEmpty()) System.err.println("  pipeline reported: ${failures.joinToString("; ")}") }
    }

    /** Remembers the result of the sink it wraps, so the run can tell whether the pipeline finished it. */
    private class RecordingSink<R : Any>(private val inner: MonoSink<R>) : MonoSink<R> {
        var result: R? = null
            private set

        override fun accept(samples: FloatArray, offset: Int, count: Int) = inner.accept(samples, offset, count)

        override fun finish(): R = inner.finish().also { result = it }
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_SECOND = 1_000.0

        /** The fallback of a host without qm-dsp: a sink that finds no grid. */
        val NO_FALLBACK: StageSinks<DjBeatGrid?> = StageSinks { rate ->
            object : MonoSinkFactory<DjBeatGrid?> {
                override val sampleRateHz: Int = rate

                override fun create(): MonoSink<DjBeatGrid?> = object : MonoSink<DjBeatGrid?> {
                    override fun accept(samples: FloatArray, offset: Int, count: Int) = Unit

                    override fun finish(): DjBeatGrid? = null
                }
            }
        }

        /** A stable id for a reference file: `cal:` + SHA-256 of its selection path (the payload is not hashed here). */
        fun audioIdOf(path: String): AudioId {
            val digest = MessageDigest.getInstance("SHA-256").digest(ReferencePaths.key(path).toByteArray())
            return AudioId.of("cal", digest.joinToString("") { "%02x".format(it) })
        }
    }
}
