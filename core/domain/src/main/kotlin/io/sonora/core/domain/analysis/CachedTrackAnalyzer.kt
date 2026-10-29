package io.sonora.core.domain.analysis

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Analyses a track through an [AnalysisCache]: audio whose content was analysed before by [analyzerVersion] is not
 * decoded again, whatever its file is called now.
 *
 * The cache only ever saves work: any failure of it (an unreadable entry, a full disk, a broken database) is reported to
 * [onCacheFailure] and the track is analysed as if nothing was cached. An unreadable entry is removed so the fresh
 * result can take its place. The first analysis also prunes the results of other analyser versions, once.
 */
class CachedTrackAnalyzer(
    private val analyzer: TrackAnalyzer,
    private val cache: AnalysisCache,
    /** The version [analyzer] stamps on its results ([TrackAnalysis.analyzerVersion]); others are not stored. */
    private val analyzerVersion: String,
    private val onCacheFailure: (Throwable) -> Unit = {},
) {
    private val pruned = AtomicBoolean(false)

    /**
     * The analysis of [sourceUri], whose bytes have the SHA-256 [contentSha256] (64 lower-case hex digits), from the
     * cache or else from the analyser. A cached result reports [onProgress] 1 at once.
     *
     * @throws io.sonora.core.domain.audio.PcmDecodeException as [TrackAnalyzer.analyze].
     * @throws AnalysisException as [TrackAnalyzer.analyze]; failures are never cached.
     */
    suspend fun analyze(sourceUri: String, contentSha256: String, onProgress: (Float) -> Unit = {}): TrackAnalysis {
        require(SHA_256_HEX.matches(contentSha256)) { "contentSha256 must be 64 lower-case hex digits: $contentSha256" }
        if (pruned.compareAndSet(false, true)) bestEffort { cache.prune(analyzerVersion) }

        val cached = try {
            cache.get(contentSha256, analyzerVersion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCacheFailure(e)
            bestEffort { cache.remove(contentSha256, analyzerVersion) }
            null
        }
        if (cached != null && cached.analyzerVersion == analyzerVersion) {
            onProgress(1f)
            return cached
        }

        val analysis = analyzer.analyze(sourceUri, onProgress)
        if (analysis.analyzerVersion == analyzerVersion) {
            bestEffort { cache.put(contentSha256, analysis) }
        } else {
            onCacheFailure(
                IllegalStateException(
                    "Analyser reported version ${analysis.analyzerVersion}, expected $analyzerVersion: not cached",
                ),
            )
        }
        return analysis
    }

    /**
     * Drops what is cached for [contentSha256] by [analyzerVersion]: for content found to have changed between being
     * hashed and being read again, whose stored analysis may belong to other bytes. Best effort, like every cache call.
     */
    suspend fun forget(contentSha256: String) = bestEffort { cache.remove(contentSha256, analyzerVersion) }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCacheFailure(e)
        }
    }

    private companion object {
        val SHA_256_HEX = Regex("[0-9a-f]{64}")
    }
}
