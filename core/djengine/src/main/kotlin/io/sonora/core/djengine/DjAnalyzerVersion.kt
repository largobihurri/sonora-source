package io.sonora.core.djengine

/**
 * The version of the DJ analysis engine, stored with every analysis (`AnalysisProvenance.analyzerVersion`). Raise it
 * whenever a change can alter results: stored analyses are never discarded, and the number tells which ones a
 * re-analysis on request would improve.
 *
 * History: 1 = D1.1 to D1.28; 2 = D1.29 (the bar phase also weighs the start of the track, `BarPhaser`: same beats and
 * tempo, a different bar position on some tracks).
 */
const val DJ_ANALYZER_VERSION: Int = 2
