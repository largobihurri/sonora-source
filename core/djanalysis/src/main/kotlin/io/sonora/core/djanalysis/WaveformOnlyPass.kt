package io.sonora.core.djanalysis

import io.sonora.core.djengine.pipeline.DjAnalysisPipeline
import io.sonora.core.djengine.pipeline.PcmSource
import io.sonora.core.djengine.pipeline.PipelineClock
import io.sonora.core.djengine.pipeline.PolyphaseResampling
import io.sonora.core.djengine.pipeline.Resampler
import io.sonora.core.djengine.pipeline.StageSinks
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.MonoSink
import kotlin.math.roundToLong

/**
 * Computes only the waveform of a track, for an analysis restored from a backup (backups carry no waveforms, user
 * decision 2026-10-07): one decode feeding only the waveform sink, with no beat model, no key and no grid.
 *
 * The result is the one [DjAnalysisPipeline] computes for the same audio, byte for byte: the same mono downmix (the
 * mean of the channels, summed in channel order, in float), the same sink choice ([waveform], the pipeline's default)
 * and, when that sink cannot run at the source rate, the same [resampler] stream flushed at the end. The waveform sink
 * does not depend on block sizes, so the block size here does not change the result.
 */
class WaveformOnlyPass(
    private val waveform: StageSinks<BandWaveform> = DjAnalysisPipeline.DEFAULT_WAVEFORM,
    private val resampler: Resampler = PolyphaseResampling,
    private val clock: PipelineClock = PipelineClock.SYSTEM,
    private val blockFrames: Int = DjAnalysisPipeline.DEFAULT_BLOCK_FRAMES,
) {
    init {
        require(blockFrames > 0) { "blockFrames must be positive: $blockFrames" }
    }

    /**
     * The waveform of [pcm], with the time spent decoding ([AnalysisStage.DECODE]) and in the sink and any resampling
     * ([AnalysisStage.WAVEFORM]). [checkCancelled] runs before every block; [onProgress] gets the decoded fraction
     * (0..1, increasing, when the length is known) and 1 at the end.
     */
    fun run(pcm: PcmSource, onProgress: (Float) -> Unit = {}, checkCancelled: () -> Unit = {}): PassResult<BandWaveform> {
        val rate = pcm.sampleRateHz
        val channels = pcm.channelCount
        require(rate > 0 && channels > 0) { "Bad PCM format: $rate Hz, $channels channels" }
        val factory = waveform.forSourceRate(rate)
        val sink = factory.create()
        val stream: MonoSink<*> = if (factory.sampleRateHz == rate) sink else resampler.into(sink, rate, factory.sampleRateHz)
        val buffer = FloatArray(blockFrames * channels)
        val mono = FloatArray(blockFrames)
        val total = pcm.totalFrames?.takeIf { it > 0 }
        var decodeNanos = 0L
        var waveformNanos = 0L
        var decoded = 0L
        var reported = -1f
        try {
            while (true) {
                checkCancelled()
                val readStart = clock.monotonicNanos()
                val frames = pcm.read(buffer)
                if (frames > 0) downmix(buffer, mono, frames, channels)
                decodeNanos += clock.monotonicNanos() - readStart
                if (frames < 0) break
                if (frames == 0) continue
                val sinkStart = clock.monotonicNanos()
                stream.accept(mono, 0, frames)
                waveformNanos += clock.monotonicNanos() - sinkStart
                decoded += frames
                if (total != null) {
                    val fraction = (decoded.toFloat() / total).coerceAtMost(MAX_BEFORE_DONE)
                    if (fraction - reported >= MIN_STEP) {
                        reported = fraction
                        onProgress(fraction)
                    }
                }
            }
            val finishStart = clock.monotonicNanos()
            if (stream !== sink) stream.finish()
            val result = sink.finish()
            waveformNanos += clock.monotonicNanos() - finishStart
            onProgress(1f)
            val stageMs = linkedMapOf(AnalysisStage.DECODE to toMs(decodeNanos), AnalysisStage.WAVEFORM to toMs(waveformNanos))
            return PassResult(result, stageMs, audioMs = (decoded * MS_PER_SECOND / rate).roundToLong())
        } finally {
            closeQuietly(stream)
            if (stream !== sink) closeQuietly(sink)
        }
    }

    /** The pipeline's downmix: the mean of the channels, summed in channel order and scaled by `1f / channels`. */
    private fun downmix(buffer: FloatArray, mono: FloatArray, frames: Int, channels: Int) {
        check(frames <= mono.size) { "The source wrote $frames frames into a buffer of ${mono.size}" }
        if (channels == 1) {
            buffer.copyInto(mono, 0, 0, frames)
            return
        }
        val scale = 1f / channels
        var at = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += buffer[at++]
            mono[f] = sum * scale
        }
    }

    private fun toMs(nanos: Long): Long = (nanos + NANOS_PER_MS / 2) / NANOS_PER_MS

    private fun closeQuietly(target: Any) {
        if (target is AutoCloseable) runCatching { target.close() }
    }

    private companion object {
        const val MS_PER_SECOND = 1_000.0
        const val NANOS_PER_MS = 1_000_000L
        const val MIN_STEP = 0.001f
        const val MAX_BEFORE_DONE = 0.999f
    }
}
