package io.sonora.core.djmodel

import io.sonora.core.domain.analysis.AnalysisException
import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.conversion.DeviceConditions
import io.sonora.core.domain.conversion.ThermalLevel
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** The stages of one analysis, in the order they run. */
enum class AnalysisStage { IDENTIFY, DECODE, WAVEFORM, MEL, INFERENCE, GRID, KEY, STORE }

/** Wall time per stage in ms (copied); [totalMs] is the sum. */
class AnalysisTimings(stageMs: Map<AnalysisStage, Long>) {
    val stageMs: Map<AnalysisStage, Long> = stageMs.toMap()

    init {
        require(this.stageMs.values.all { it >= 0 }) { "Stage times must not be negative" }
    }

    val totalMs: Long get() = stageMs.values.sum()

    override fun equals(other: Any?): Boolean = other is AnalysisTimings && stageMs == other.stageMs

    override fun hashCode(): Int = stageMs.hashCode()

    override fun toString(): String = "AnalysisTimings($stageMs)"
}

/** Receives what the analysis did, for the log (logcat tag `SonoraDjAnalysis` on Android). */
interface AnalysisTelemetry {

    fun analysed(audioId: AudioId, timings: AnalysisTimings, provenance: AnalysisProvenance)

    fun failed(stage: AnalysisStage, cause: Throwable)
}

/**
 * Why an analysis failed, in terms a screen can word for the user ([of] reads the exception; the analyser's own text stays
 * in [AnalysisStatus.Failed.reason], for the log).
 */
enum class AnalysisFailureKind {
    /** The file cannot be read: missing, removed storage or revoked permission. */
    UNREADABLE,

    /** No decoder for the format, or a sample rate the analyser does not support. */
    UNSUPPORTED,

    /** The audio is damaged or truncated. */
    CORRUPT,

    /** The track is shorter than the analyser needs. */
    TOO_SHORT,

    /** Anything else (an internal error, a cancellation nobody asked for). */
    OTHER;

    companion object {
        fun of(failure: Throwable): AnalysisFailureKind = when (failure) {
            // The decoder's exceptions are IOExceptions: the specific ones come before the general I/O failure.
            is PcmDecodeException.UnsupportedFormat, is AnalysisException.UnsupportedSampleRate -> UNSUPPORTED
            is PcmDecodeException.CorruptData -> CORRUPT
            is AnalysisException.TooShort -> TOO_SHORT
            is PcmDecodeException.SourceUnavailable, is IOException, is SecurityException -> UNREADABLE
            else -> OTHER
        }
    }
}

/**
 * The analysis state of one track, as the DJ screen shows it. An [Analysing] run with [Analysing.replacing] set is a
 * re-analysis of a track that already has a stored analysis (asked for by the user): the screen keeps showing the stored
 * one while it runs.
 */
sealed interface AnalysisStatus {
    data object NotAnalysed : AnalysisStatus
    data object Queued : AnalysisStatus
    data class Analysing(val stage: AnalysisStage, val progress: Float, val replacing: Boolean = false) : AnalysisStatus
    data object Analysed : AnalysisStatus
    data class Failed(val reason: String, val kind: AnalysisFailureKind = AnalysisFailureKind.OTHER) : AnalysisStatus
}

/**
 * The device conditions the analysis reacts to: [batterySaver], [thermalThrottled] (a thermal status of severe or worse)
 * and the finer [thermalLevel], which the performance budget steps down on (moderate heat) before it gets that far.
 * Battery saver or severe heat pauses background analysis (never the track being played).
 */
data class DeviceLoad(
    val batterySaver: Boolean = false,
    val thermalThrottled: Boolean = false,
    val thermalLevel: ThermalLevel = ThermalLevel.NONE,
) {
    /** Severe heat or worse, from either field. */
    val hot: Boolean get() = thermalThrottled || thermalLevel >= ThermalLevel.SEVERE

    val backgroundAllowed: Boolean get() = !batterySaver && !hot

    /** The conditions in the form [PerformancePolicy] takes. */
    fun conditions(): DeviceConditions = DeviceConditions(
        thermal = if (thermalThrottled) maxOf(thermalLevel, ThermalLevel.SEVERE) else thermalLevel,
        powerSaveMode = batterySaver,
    )
}

/** What the scheduler decides from: the playing track's [playingUri], the [upNextUris] in queue order, the device load. */
data class SchedulingInput(
    val djModeOn: Boolean,
    val playingUri: String?,
    val upNextUris: List<String>,
    val load: DeviceLoad = DeviceLoad(),
)

/** Analyses one file. The result is not stored: the caller owns persistence. */
interface DjTrackAnalyzer {

    /**
     * Analyses [sourceUri] (identified as [audioId]), reporting the stage and its fraction done (0..1) as it goes.
     *
     * @throws io.sonora.core.domain.analysis.AnalysisException when the audio cannot be analysed.
     * @throws io.sonora.core.domain.audio.PcmDecodeException when it cannot be decoded.
     */
    suspend fun analyze(
        sourceUri: String,
        audioId: AudioId,
        onProgress: (stage: AnalysisStage, fraction: Float) -> Unit = { _, _ -> },
    ): DjAnalysis
}

/** The permanent store of analyses, keyed by audio identity; nothing is ever discarded by an upgrade. */
interface DjAnalysisStore {

    suspend fun get(id: AudioId): DjAnalysis?

    /** Emits the analysis of [id] now and after every change; `null` while there is none. */
    fun observe(id: AudioId): Flow<DjAnalysis?>

    /** The subset of [ids] that already have an analysis. */
    suspend fun analysedAmong(ids: Collection<AudioId>): Set<AudioId>

    /** Stores [analysis] unless [id] already has one (the first result wins); returns whether it was stored. */
    suspend fun putIfAbsent(id: AudioId, analysis: DjAnalysis): Boolean

    /** Stores [analysis] for [id], replacing any previous one (re-analysis on request). */
    suspend fun replace(id: AudioId, analysis: DjAnalysis)

    /**
     * The subset of [ids] whose analysis is stored without its waveform ([DjAnalysis.waveformMissing]): analyses restored
     * from a backup. [analysedAmong] counts them as analysed, so whoever schedules work must also ask this, and run only
     * the waveform stage for the ids it returns, then [fillMissingWaveform].
     */
    suspend fun waveformMissingAmong(ids: Collection<AudioId>): Set<AudioId> =
        ids.filterTo(HashSet()) { get(it)?.waveformMissing == true }

    /**
     * Stores [waveform] for [id] if its analysis is stored without one, keeping everything else in the row (the grid,
     * the key and any edit of them are never touched); returns whether it was stored. `false` for an unknown id, for an
     * analysis that already has its waveform (it is never replaced here) and for one that cannot be read.
     */
    suspend fun fillMissingWaveform(id: AudioId, waveform: BandWaveform): Boolean {
        require(waveform.columnCount > 0) { "A waveform without columns fills nothing" }
        val current = get(id)?.takeIf { it.waveformMissing } ?: return false
        replace(id, current.copy(waveform = waveform))
        return true
    }
}
