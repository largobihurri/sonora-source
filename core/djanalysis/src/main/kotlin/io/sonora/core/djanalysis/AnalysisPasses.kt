package io.sonora.core.djanalysis

import io.sonora.core.djengine.pipeline.DjAnalysisPipeline
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.DjAnalysis

/** What one pass over a track produced, with the wall time of its stages and the length of the audio it decoded. */
class PassResult<out T>(
    val value: T,
    val stageMs: Map<AnalysisStage, Long>,
    val audioMs: Long,
    val provenance: AnalysisProvenance? = null,
)

/** The two kinds of work done on a track (the app's [AndroidDjTrackAnalyzer], a fake in tests). */
interface AnalysisPasses {

    /** The whole analysis of [sourceUri] (identified as [audioId]); not stored. */
    suspend fun full(
        sourceUri: String,
        audioId: AudioId,
        onProgress: (AnalysisStage, Float) -> Unit,
    ): PassResult<DjAnalysis>

    /**
     * Only the waveform of [sourceUri] (decode + waveform sink), exactly as [full] would compute it: for an analysis
     * restored from a backup, which has everything else. Never a waveform without columns.
     */
    suspend fun waveform(sourceUri: String, onProgress: (AnalysisStage, Float) -> Unit): PassResult<BandWaveform>
}

/** Maps the pipeline's single progress value to the stage the user sees. */
object PipelineProgress {

    /**
     * The stage running at overall progress [fraction] (0..1) of a [DjAnalysisPipeline] run: with a model the decode
     * fills up to [DjAnalysisPipeline.DECODE_SHARE_WITH_MODEL] and inference up to [DjAnalysisPipeline.INFERENCE_END];
     * without one the decode fills up to [DjAnalysisPipeline.DECODE_SHARE_WITHOUT_MODEL]. The rest is the grid, the key
     * and the waveform finishing ([AnalysisStage.GRID]).
     */
    fun stageAt(fraction: Float, withModel: Boolean): AnalysisStage = when {
        withModel && fraction < DjAnalysisPipeline.DECODE_SHARE_WITH_MODEL -> AnalysisStage.DECODE
        withModel && fraction < DjAnalysisPipeline.INFERENCE_END -> AnalysisStage.INFERENCE
        !withModel && fraction < DjAnalysisPipeline.DECODE_SHARE_WITHOUT_MODEL -> AnalysisStage.DECODE
        else -> AnalysisStage.GRID
    }
}
