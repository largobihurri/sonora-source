package io.sonora.core.djanalysis

import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AudioId
import java.util.Locale

/** Where the DJ analysis writes its log lines: logcat in the app ([AndroidDjAnalysisLog]), a list in tests. */
interface DjAnalysisLog {
    fun info(message: String)

    fun warn(message: String, cause: Throwable?)
}

/**
 * Logcat, tag [TAG]. ColorOS hides app logs unless enabled: `adb shell setprop log.tag.SonoraDjAnalysis VERBOSE`.
 */
object AndroidDjAnalysisLog : DjAnalysisLog {
    const val TAG: String = "SonoraDjAnalysis"

    override fun info(message: String) {
        android.util.Log.i(TAG, message)
    }

    override fun warn(message: String, cause: Throwable?) {
        android.util.Log.w(TAG, message, cause)
    }
}

/**
 * Which work a run did: a whole analysis, only the waveform of an analysis restored from a backup, or a whole analysis
 * that replaced the stored one on the user's request.
 */
enum class AnalysisKind { FULL, WAVEFORM_ONLY, REANALYSIS }

/**
 * One analysed track, as the log line [DjAnalysisReport.line] prints it for the device timings (D1.24): the wall time of
 * every stage that ran ([stageMs], including the caller's [AnalysisStage.IDENTIFY] and [AnalysisStage.STORE]), the
 * length of the audio, where the grid came from ([beatSourceId], `null` for a waveform-only run), the [accelerator],
 * whether the result was [stored] and whether the build is debuggable (a debuggable build runs far slower: its timings
 * are not the phone's).
 */
data class DjAnalysisReport(
    val audioId: AudioId,
    val kind: AnalysisKind,
    val audioMs: Long,
    val stageMs: Map<AnalysisStage, Long>,
    val beatSourceId: String?,
    val accelerator: Accelerator?,
    val stored: Boolean,
    val debuggable: Boolean,
) {
    val totalMs: Long get() = stageMs.values.sum()

    /**
     * `analysed id=ap1:0123456789ab kind=full audio=361.2s total=9876ms speed=36.6x source=beatthis-small0/v
     * accelerator=CPU stored=true debuggable=false IDENTIFY=120 DECODE=2100 ...` (stages in pipeline order, only those
     * that ran; `speed` is audio time over total time, absent when the total is 0).
     */
    fun line(): String = buildString {
        append("analysed id=").append(audioId.scheme).append(':').append(audioId.hash.take(ID_DIGITS))
        append(" kind=").append(kind.name.lowercase(Locale.ROOT))
        append(" audio=").append(String.format(Locale.ROOT, "%.1f", audioMs / MS_PER_SECOND)).append('s')
        append(" total=").append(totalMs).append("ms")
        if (totalMs > 0) append(" speed=").append(String.format(Locale.ROOT, "%.1f", audioMs.toDouble() / totalMs)).append('x')
        append(" source=").append(beatSourceId ?: "none")
        append(" accelerator=").append(accelerator?.name ?: "none")
        append(" stored=").append(stored)
        append(" debuggable=").append(debuggable)
        for (stage in AnalysisStage.entries) {
            stageMs[stage]?.let { append(' ').append(stage.name).append('=').append(it) }
        }
    }

    private companion object {
        const val ID_DIGITS = 12
        const val MS_PER_SECOND = 1_000.0
    }
}
