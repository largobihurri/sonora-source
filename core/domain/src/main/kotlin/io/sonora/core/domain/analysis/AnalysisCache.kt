package io.sonora.core.domain.analysis

/**
 * Keeps [TrackAnalysis] results by audio content, so the same audio is analysed once per analyser version.
 *
 * An entry is keyed by the SHA-256 of the file's bytes (64 lower-case hex digits) and [TrackAnalysis.analyzerVersion]:
 * a renamed or moved file still hits, while a new analyser version misses. Implementations bound their size, dropping
 * the least recently used entries first, and report an entry they cannot read back as a miss, removing it.
 */
interface AnalysisCache {

    /** The analysis stored for [contentSha256] by [analyzerVersion], or `null` when there is none (or it is unreadable). */
    suspend fun get(contentSha256: String, analyzerVersion: String): TrackAnalysis?

    /** Stores [analysis] for [contentSha256] under its own [TrackAnalysis.analyzerVersion], replacing any earlier one. */
    suspend fun put(contentSha256: String, analysis: TrackAnalysis)

    /** Removes the entry for [contentSha256] and [analyzerVersion], if there is one. */
    suspend fun remove(contentSha256: String, analyzerVersion: String)

    /** Removes every entry of an analyser version other than [keepAnalyzerVersion]: they can never hit again. */
    suspend fun prune(keepAnalyzerVersion: String)
}
