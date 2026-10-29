package io.sonora.core.analysis

import io.sonora.core.analysis.di.AnalysisDispatcher
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform
import io.sonora.core.domain.analysis.MusicalKey
import io.sonora.core.domain.analysis.TrackAnalysis
import io.sonora.core.domain.analysis.TrackAnalyzer
import io.sonora.core.domain.audio.PcmDecoder
import io.sonora.core.domain.audio.PcmFormat
import io.sonora.core.domain.audio.PcmSamples
import io.sonora.core.domain.audio.PcmStream
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [TrackAnalyzer] that decodes the track with [PcmDecoder], downmixes it to mono and runs it through an
 * [AnalysisEngine] (the native qm-dsp analyser in the app) and a [BlueWaveformBuilder], in a single decode. The engine
 * and the stream are always released, also on failure or cancellation; cancellation is checked between blocks, so it
 * takes effect within one block.
 */
class AndroidTrackAnalyzer @Inject constructor(
    private val decoder: PcmDecoder,
    private val engines: AnalysisEngineFactory,
    @AnalysisDispatcher private val dispatcher: CoroutineDispatcher,
) : TrackAnalyzer {

    override suspend fun analyze(sourceUri: String, onProgress: (Float) -> Unit): TrackAnalysis =
        withContext(dispatcher) {
            decoder.open(sourceUri).use { stream -> analyze(stream, onProgress) }
        }

    /** @throws AnalysisException.UnsupportedSampleRate when the engine or the waveform cannot take the rate. */
    private suspend fun analyze(stream: PcmStream, onProgress: (Float) -> Unit): TrackAnalysis {
        val format = stream.format
        val channels = format.channelCount
        val progress = Progress(expectedFrames(format), onProgress)
        if (format.sampleRateHz !in BlueWaveformBuilder.MIN_SAMPLE_RATE_HZ..BlueWaveformBuilder.MAX_SAMPLE_RATE_HZ) {
            throw AnalysisException.UnsupportedSampleRate(format.sampleRateHz)
        }
        return engines.create(format.sampleRateHz).use { engine ->
            val waveform = BlueWaveformBuilder(format.sampleRateHz)
            val buffer = FloatArray(PcmStream.DEFAULT_CHUNK_FRAMES * channels)
            var frames = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                PcmSamples.downmixToMono(buffer, read, channels, buffer)
                waveform.process(buffer, read)
                engine.process(buffer, read)
                frames += read
                progress.decoded(frames)
            }
            currentCoroutineContext().ensureActive()
            val result = engine.finish()
            val analysis = result.toAnalysis(format.sampleRateHz, waveform.finish())
            onProgress(1f)
            analysis
        }
    }

    /** @throws AnalysisException.EngineFailure when the engine reported values no beat grid can have. */
    private fun EngineResult.toAnalysis(sampleRateHz: Int, waveform: BlueWaveform): TrackAnalysis {
        if (!bpm.isFinite() || bpm <= 0 || !firstBeatFrame.isFinite() || firstBeatInBar !in 1..BeatGrid.BEATS_PER_BAR ||
            totalFrames < 0
        ) {
            throw AnalysisException.EngineFailure(
                "invalid engine result: bpm=$bpm firstBeatFrame=$firstBeatFrame firstBeatInBar=$firstBeatInBar " +
                    "totalFrames=$totalFrames",
            )
        }
        val msPerFrame = 1_000.0 / sampleRateHz
        val durationMs = totalFrames * msPerFrame
        return TrackAnalysis(
            beatGrid = BeatGrid.spanning(bpm, firstBeatFrame * msPerFrame, firstBeatInBar, durationMs),
            key = MusicalKey.fromIndex(keyIndex),
            waveform = waveform,
            durationMs = totalFrames * 1_000 / sampleRateHz,
            analyzerVersion = ANALYZER_VERSION,
        )
    }

    private fun expectedFrames(format: PcmFormat): Long? =
        (format.totalFrames ?: format.durationUs?.let { it * format.sampleRateHz / 1_000_000 })?.takeIf { it > 0 }

    /** Reports decoding progress as up to [DECODE_SHARE] of the work, in steps of at least [MIN_STEP]. */
    private class Progress(private val expectedFrames: Long?, private val onProgress: (Float) -> Unit) {
        private var reported = 0f

        fun decoded(frames: Long) {
            val expected = expectedFrames ?: return
            val fraction = (frames.toFloat() / expected).coerceAtMost(1f) * DECODE_SHARE
            if (fraction - reported >= MIN_STEP) {
                reported = fraction
                onProgress(fraction)
            }
        }
    }

    companion object {
        /** Identifies the algorithm and its parameters; change it whenever results would change. */
        const val ANALYZER_VERSION: String = "sonora-analysis/2 (qm-dsp mixxx@332b8026, blue waveform 2)"

        /** Decoding and per-block analysis; the rest (beat tracking, grid, downbeats, key) happens in finish. */
        private const val DECODE_SHARE = 0.95f
        private const val MIN_STEP = 0.01f
    }
}
