package io.sonora.core.djmodel

import io.sonora.core.domain.conversion.PerformanceMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether DJ mode is on, and how hard its analysis may work. [enabled] gates the analysis: nothing is identified,
 * decoded or analysed while it is off, which is the default until the user turns it on (Now Playing's "…" menu and
 * Settings both flip it).
 */
interface DjModeSettings {
    val enabled: StateFlow<Boolean>

    fun setEnabled(enabled: Boolean)

    /**
     * The performance the user chose for the analysis ([PerformanceMode.DEFAULT] until then), as the converter's mode:
     * [DjAnalysisBudget] turns it, with the device's heat and battery saver, into threads, priority and look-ahead.
     */
    val performance: StateFlow<PerformanceMode>

    fun setPerformance(performance: PerformanceMode)
}

/**
 * The analysis state per track uri, for the screens that show it (Now Playing's DJ chip, the DJ view). A track the
 * analysis has not reported yet has no entry. Reading [status] may start the analysis queue, so a screen reads it only
 * while DJ mode is on.
 */
interface DjAnalysisStatusSource {
    val status: StateFlow<Map<String, AnalysisStatus>>
}

/**
 * What a DJ screen can ask of the analysis for the track playing now (D2.11). Both are ignored while DJ mode is off or for
 * a track the analysis is not looking at; the answer is seen in [DjAnalysisStatusSource].
 */
interface DjAnalysisControl {

    /** Gives a track whose analysis failed one new attempt. It never replaces a stored analysis. */
    fun retry(uri: String)

    /**
     * Analyses the track again and replaces its stored analysis (the user's grid edit and cues are kept apart and are not
     * touched). The playing track goes first.
     */
    fun reanalyse(uri: String)
}

/** Whether the analysis is waiting for the phone to cool down, as the DJ screen tells the user. */
interface DjAnalysisPauseSource {

    /** `true` while the analysis stands still because the device is hot (the low-resources budget pauses on severe heat). */
    val pausedByHeat: StateFlow<Boolean>
}

/** The analyser this build ships, to tell a stored analysis from an older one ([DjAnalysis.isOlderThan]). */
interface DjAnalyzerInfo {
    val version: Int
}
