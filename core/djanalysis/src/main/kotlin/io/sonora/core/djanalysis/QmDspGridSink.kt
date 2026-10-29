package io.sonora.core.djanalysis

import io.sonora.core.analysis.AnalysisEngine
import io.sonora.core.analysis.AnalysisEngineFactory
import io.sonora.core.analysis.EngineResult
import io.sonora.core.djengine.pipeline.StageSinks
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.analysis.BeatGrid
import kotlin.math.ceil

/** Turns what the qm-dsp engine found into a one-segment [DjBeatGrid]. */
object QmDspGrid {

    /**
     * The constant grid of [result], whose frames are at [sampleRateHz]: one [TempoSegment] at the first beat, with
     * its tempo and bar position. A first beat a little before frame 0 (rounding in the engine) moves on by whole beats
     * to the first one at or after 0, keeping the bar position in step.
     *
     * @throws AnalysisException.EngineFailure when the values cannot describe a grid (tempo not positive, a bar
     *   position outside 1..4, a non-finite first beat).
     */
    fun toDjBeatGrid(result: EngineResult, sampleRateHz: Int): DjBeatGrid {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        val bpm = result.bpm
        if (!bpm.isFinite() || bpm <= 0 || !result.firstBeatFrame.isFinite() || result.firstBeatInBar !in 1..BeatGrid.BEATS_PER_BAR) {
            throw AnalysisException.EngineFailure(
                "invalid qm-dsp result: bpm=$bpm firstBeatFrame=${result.firstBeatFrame} firstBeatInBar=${result.firstBeatInBar}",
            )
        }
        val beatMs = MS_PER_MINUTE / bpm
        var startMs = result.firstBeatFrame * MS_PER_SECOND / sampleRateHz
        var beatInBar = result.firstBeatInBar
        if (startMs < 0) {
            val beats = ceil(-startMs / beatMs).toLong()
            startMs += beats * beatMs
            beatInBar = Math.floorMod(beatInBar - 1 + beats, BeatGrid.BEATS_PER_BAR.toLong()).toInt() + 1
        }
        return DjBeatGrid(listOf(TempoSegment(startMs, bpm, beatInBar)))
    }

    private const val MS_PER_SECOND = 1_000.0
    private const val MS_PER_MINUTE = 60_000.0
}

/**
 * The qm-dsp analyser of `:core:analysis` ([AnalysisEngineFactory], the native engine in the app) as the pipeline's
 * fallback grid stage. Its sinks run at the source rate when the engine supports it (8..192 kHz), else at 44.1 kHz.
 *
 * A sink owns a native engine, so it is [AutoCloseable]: the pipeline closes it at the end of every run, including the
 * runs whose model grid made it unnecessary (then it is never finished). Failures of the engine while it is fed (it
 * cannot be created, it runs out of memory) are kept and thrown by [MonoSink.finish] only, so they matter only when the
 * fallback grid is actually needed. On finish, audio too short for the engine or without a steady beat gives a `null`
 * grid (beatless); any other engine failure is thrown.
 */
class QmDspGridSink(private val engines: AnalysisEngineFactory) : StageSinks<DjBeatGrid?> {

    override fun forSourceRate(sourceRateHz: Int): MonoSinkFactory<DjBeatGrid?> {
        val rate = if (sourceRateHz in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) sourceRateHz else DEFAULT_SAMPLE_RATE_HZ
        return object : MonoSinkFactory<DjBeatGrid?> {
            override val sampleRateHz: Int = rate

            override fun create(): MonoSink<DjBeatGrid?> = Sink(engines, rate)
        }
    }

    /** One run of the engine; see the class comment for when its failures surface. */
    private class Sink(engines: AnalysisEngineFactory, private val sampleRateHz: Int) : MonoSink<DjBeatGrid?>, AutoCloseable {
        private var failure: Exception? = null
        private var engine: AnalysisEngine? = try {
            engines.create(sampleRateHz)
        } catch (e: Exception) {
            failure = e
            null
        }

        /** The engine reads from index 0, so a block at an offset is copied here first (grown, never per block). */
        private var scratch = FloatArray(0)

        override fun accept(samples: FloatArray, offset: Int, count: Int) {
            val target = engine ?: return
            if (failure != null || count <= 0) return
            val block = if (offset == 0) {
                samples
            } else {
                if (scratch.size < count) scratch = FloatArray(maxOf(count, scratch.size * 2))
                samples.copyInto(scratch, 0, offset, offset + count)
                scratch
            }
            try {
                target.process(block, count)
            } catch (e: Exception) {
                failure = e
            }
        }

        override fun finish(): DjBeatGrid? {
            failure?.let { throw it }
            val target = checkNotNull(engine) { "The qm-dsp engine is closed" }
            val result = try {
                target.finish()
            } catch (e: AnalysisException.TooShort) {
                return null
            } catch (e: AnalysisException.NoBeatsDetected) {
                return null
            }
            return QmDspGrid.toDjBeatGrid(result, sampleRateHz)
        }

        override fun close() {
            val target = engine ?: return
            engine = null
            target.close()
        }
    }

    companion object {
        const val MIN_SAMPLE_RATE_HZ: Int = 8_000
        const val MAX_SAMPLE_RATE_HZ: Int = 192_000
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 44_100
    }
}
