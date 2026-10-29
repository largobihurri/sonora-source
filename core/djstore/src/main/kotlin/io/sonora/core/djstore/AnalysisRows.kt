package io.sonora.core.djstore

import io.sonora.core.djengine.codec.DjAnalysisCodec
import io.sonora.core.djengine.codec.DjCodecException
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatSource
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djstore.db.DjAnalysisEntity
import io.sonora.core.domain.analysis.MusicalKey

/** What a stored row is to this build. */
internal sealed interface RowState {

    /** A row this build reads in full. */
    class Readable(val analysis: DjAnalysis) : RowState

    /**
     * A row written by a later build (a newer row layout, a newer SDJ1 version, a code this build does not know). It is
     * not damaged: the store keeps it untouched, the track counts as not analysed, and only an explicit replace
     * overwrites it.
     */
    class Unsupported(val reason: String) : RowState

    /** A row that cannot be read and never will be: a bad checksum, a truncated blob, an impossible value. */
    class Damaged(val reason: String, val cause: Throwable? = null) : RowState
}

/** Maps between [DjAnalysis] and its [DjAnalysisEntity] row. */
internal object AnalysisRows {

    // Explicit stored codes: reordering or renaming a Kotlin enum must never change what a row means.
    private const val BEAT_SOURCE_MODEL = 0
    private const val BEAT_SOURCE_QM_DSP = 1
    private const val ACCELERATOR_CPU = 0
    private const val ACCELERATOR_GPU = 1
    private const val ACCELERATOR_NPU = 2

    fun toEntity(id: AudioId, analysis: DjAnalysis): DjAnalysisEntity {
        val provenance = analysis.provenance
        val key = analysis.key
        return DjAnalysisEntity(
            audioId = id.value,
            analyzerVersion = provenance.analyzerVersion,
            beatSource = beatSourceCode(provenance.beatSource),
            modelId = provenance.modelId,
            accelerator = provenance.accelerator?.let(::acceleratorCode),
            createdAtMs = provenance.analysedAtEpochMs,
            durationMs = analysis.durationMs,
            keyIndex = key?.key?.index,
            keyStrength = key?.strength,
            keyMethod = key?.method,
            grid = analysis.grid?.let(DjAnalysisCodec::encodeGrid),
            waveform = DjAnalysisCodec.encodeWaveform(analysis.waveform),
            waveformVersion = if (analysis.waveformMissing) DjAnalysisEntity.WAVEFORM_MISSING else analysis.waveform.version,
            recordVersion = DjAnalysisEntity.RECORD_VERSION,
        )
    }

    /**
     * What [row] is to this build. With [decodeWaveform] false the waveform blob is not looked at and the analysis
     * carries [BandWaveform.MISSING]: the backup reads rows that way, as it never keeps a waveform.
     */
    fun read(row: DjAnalysisEntity, decodeWaveform: Boolean = true): RowState {
        if (row.recordVersion > DjAnalysisEntity.RECORD_VERSION) {
            return RowState.Unsupported("record version ${row.recordVersion}")
        }
        if (row.recordVersion < 1) return RowState.Damaged("record version ${row.recordVersion}")
        val grid = row.grid?.let { bytes ->
            try {
                DjAnalysisCodec.decodeGrid(bytes)
            } catch (e: DjCodecException) {
                return failed("grid", e)
            }
        }
        val waveform = if (decodeWaveform) {
            try {
                DjAnalysisCodec.decodeWaveform(row.waveform)
            } catch (e: DjCodecException) {
                return failed("waveform", e)
            }
        } else {
            BandWaveform.MISSING
        }
        val missing = row.waveformVersion == DjAnalysisEntity.WAVEFORM_MISSING
        if (decodeWaveform && missing && waveform.columnCount != 0) {
            return RowState.Damaged("waveform marked as missing but holds ${waveform.columnCount} columns")
        }
        val beatSource = beatSourceOf(row.beatSource)
            ?: return RowState.Unsupported("beat source code ${row.beatSource}")
        val accelerator = row.accelerator?.let { code ->
            acceleratorOf(code) ?: return RowState.Unsupported("accelerator code $code")
        }
        return try {
            RowState.Readable(
                DjAnalysis(
                    durationMs = row.durationMs,
                    grid = grid,
                    key = keyOf(row),
                    // A row marked as missing reads with the placeholder, whatever its (empty) blob decoded to.
                    waveform = if (missing) BandWaveform.MISSING else waveform,
                    provenance = AnalysisProvenance(
                        analyzerVersion = row.analyzerVersion,
                        beatSource = beatSource,
                        modelId = row.modelId,
                        accelerator = accelerator,
                        analysedAtEpochMs = row.createdAtMs,
                    ),
                ),
            )
        } catch (e: IllegalArgumentException) {
            // A value the model refuses (a negative duration, a model id without the model source, a bad key).
            RowState.Damaged("invalid value: ${e.message}", e)
        }
    }

    /** A newer SDJ1 version is a row to keep; anything else wrong with a blob is damage. */
    private fun failed(what: String, e: DjCodecException): RowState = when (e) {
        is DjCodecException.UnsupportedVersion -> RowState.Unsupported("$what blob format version ${e.version}")
        else -> RowState.Damaged("$what blob: ${e.message}", e)
    }

    private fun keyOf(row: DjAnalysisEntity): KeyEstimate? {
        val index = row.keyIndex ?: run {
            require(row.keyStrength == null && row.keyMethod == null) { "key strength or method without a key" }
            return null
        }
        val key = requireNotNull(MusicalKey.fromIndex(index)) { "unknown key index $index" }
        return KeyEstimate(
            key,
            requireNotNull(row.keyStrength) { "key without strength" },
            requireNotNull(row.keyMethod) { "key without method" },
        )
    }

    private fun beatSourceCode(source: BeatSource): Int = when (source) {
        BeatSource.MODEL -> BEAT_SOURCE_MODEL
        BeatSource.QM_DSP -> BEAT_SOURCE_QM_DSP
    }

    private fun beatSourceOf(code: Int): BeatSource? = when (code) {
        BEAT_SOURCE_MODEL -> BeatSource.MODEL
        BEAT_SOURCE_QM_DSP -> BeatSource.QM_DSP
        else -> null
    }

    private fun acceleratorCode(accelerator: Accelerator): Int = when (accelerator) {
        Accelerator.CPU -> ACCELERATOR_CPU
        Accelerator.GPU -> ACCELERATOR_GPU
        Accelerator.NPU -> ACCELERATOR_NPU
    }

    private fun acceleratorOf(code: Int): Accelerator? = when (code) {
        ACCELERATOR_CPU -> Accelerator.CPU
        ACCELERATOR_GPU -> Accelerator.GPU
        ACCELERATOR_NPU -> Accelerator.NPU
        else -> null
    }
}
