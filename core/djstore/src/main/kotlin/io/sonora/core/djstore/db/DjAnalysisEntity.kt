package io.sonora.core.djstore.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One analysed track, as the store keeps it. The grid and the waveform are SDJ1 BLOBs (`DjAnalysisCodec`), which carry
 * their own format version and checksum; the other columns are plain values.
 *
 * The enums are stored as explicit codes (see `AnalysisRows`), never as ordinals or names, so reordering or renaming a
 * Kotlin enum cannot change what a stored row means. Equality compares the BLOB contents byte by byte, which is only
 * meant to tell whether a re-read row is the same row: never compare two separately encoded waveforms, the compressed
 * bytes depend on the zlib build.
 */
@Entity(tableName = "analyses")
data class DjAnalysisEntity(
    /** `AudioId.value`. */
    @PrimaryKey @ColumnInfo(name = "audio_id") val audioId: String,
    @ColumnInfo(name = "analyzer_version") val analyzerVersion: Int,
    /** 0 MODEL, 1 QM_DSP. */
    @ColumnInfo(name = "beat_source") val beatSource: Int,
    @ColumnInfo(name = "model_id") val modelId: String?,
    /** 0 CPU, 1 GPU, 2 NPU; `null` when not recorded. */
    @ColumnInfo(name = "accelerator") val accelerator: Int?,
    /** When the analysis was made, epoch milliseconds (`AnalysisProvenance.analysedAtEpochMs`). */
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** `MusicalKey.index` (1..24); the three key columns are all `null` or all set. */
    @ColumnInfo(name = "key_index") val keyIndex: Int?,
    @ColumnInfo(name = "key_strength") val keyStrength: Double?,
    @ColumnInfo(name = "key_method") val keyMethod: String?,
    /** SDJ1 grid record; `null` for audio without a steady beat. */
    @ColumnInfo(name = "grid") val grid: ByteArray?,
    /** SDJ1 waveform record. */
    @ColumnInfo(name = "waveform") val waveform: ByteArray,
    /**
     * `BandWaveform.version`, so a waveform made by an older algorithm can be found without decoding it. It is
     * [WAVEFORM_MISSING] (0, never a real version) for an analysis restored from a backup, whose [waveform] blob then
     * holds an SDJ1 waveform of no columns until the analyser computes the real one.
     */
    @ColumnInfo(name = "waveform_version") val waveformVersion: Int,
    /** The layout of this row; a row with a newer one than [RECORD_VERSION] was written by a later build. */
    @ColumnInfo(name = "record_version") val recordVersion: Int,
) {
    override fun equals(other: Any?): Boolean = other is DjAnalysisEntity &&
        audioId == other.audioId &&
        analyzerVersion == other.analyzerVersion &&
        beatSource == other.beatSource &&
        modelId == other.modelId &&
        accelerator == other.accelerator &&
        createdAtMs == other.createdAtMs &&
        durationMs == other.durationMs &&
        keyIndex == other.keyIndex &&
        keyStrength == other.keyStrength &&
        keyMethod == other.keyMethod &&
        grid.contentEquals(other.grid) &&
        waveform.contentEquals(other.waveform) &&
        waveformVersion == other.waveformVersion &&
        recordVersion == other.recordVersion

    override fun hashCode(): Int {
        var h = audioId.hashCode()
        h = h * 31 + analyzerVersion
        h = h * 31 + createdAtMs.hashCode()
        h = h * 31 + (grid?.contentHashCode() ?: 0)
        h = h * 31 + waveform.contentHashCode()
        return h
    }

    override fun toString(): String = "DjAnalysisEntity($audioId, analyzer $analyzerVersion, record $recordVersion)"

    companion object {
        /** The row layout this build writes and reads. */
        const val RECORD_VERSION: Int = 1

        /**
         * The `waveform_version` of a row whose waveform has not been computed (restored from a backup). The queries of
         * `DjAnalysisDao` that find or fill such rows spell it as the literal 0; a test pins the two together.
         */
        const val WAVEFORM_MISSING: Int = 0
    }
}
