package io.sonora.core.djanalysis

import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.DjAnalysisStatusSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * The remembered [AudioId] of a track's uri, as the DJ screens find it: the file's stamp (size and last-modified time,
 * read on [dispatcher]) and the memo ([AudioIdIndex]) asked with it, so an id remembered for a file that has changed
 * since is a miss. It never hashes and never opens the file; the scheduler does the identifying.
 */
internal class TrackAudioIds(
    private val documents: AudioDocuments,
    private val index: AudioIdIndex,
    private val dispatcher: CoroutineDispatcher,
) {

    /** The remembered id of [uri], or `null` when there is none, the file changed, or the provider failed. */
    suspend fun idOf(uri: String): AudioId? = try {
        val stamp = withContext(dispatcher) { documents.stamp(uri) }
        index.idFor(uri, stamp)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // a provider that cannot answer (access revoked, ...): not identified until the next look
    }

    /**
     * The id of [uri] now and whenever it may have changed: the lookup is repeated whenever the uri's analysis status
     * in [status] moves to a new step (queued, a new stage, analysed, failed; progress inside a stage does not count),
     * which is exactly when the scheduler may have identified and remembered the file. A newer status cancels a
     * lookup still running.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // mapLatest
    fun follow(status: DjAnalysisStatusSource, uri: String): Flow<AudioId?> = status.status
        .map { it[uri] }
        .distinctUntilChanged(::sameStep)
        .mapLatest { idOf(uri) }
        .distinctUntilChanged()

    private fun sameStep(a: AnalysisStatus?, b: AnalysisStatus?): Boolean =
        a == b || (a is AnalysisStatus.Analysing && b is AnalysisStatus.Analysing && a.stage == b.stage)
}
