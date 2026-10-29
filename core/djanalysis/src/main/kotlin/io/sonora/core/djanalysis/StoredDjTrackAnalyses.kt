package io.sonora.core.djanalysis

import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.AudioIdIndex
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjAnalysisStore
import io.sonora.core.djmodel.DjGridEditStore
import io.sonora.core.djmodel.DjTrackAnalyses
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * The [DjTrackAnalyses] of the app: the uri's [AudioId] from the memo ([AudioIdIndex]), the analysis of that id from the
 * [store] and the user's grid edit of the same id from [edits], joined into one [DjAnalysis] whose `gridEdit` is the
 * edit (so an edit saved or removed reaches the screens as soon as it is written). An edit with no analysis shows
 * nothing: there is no track to edit yet.
 *
 * It never identifies: it has no hasher and never opens the file, so showing the playing track costs no second read
 * while the scheduler hashes it. The id is therefore not known until the scheduler has identified the file and
 * remembered it, so the lookup is repeated whenever the uri's analysis status moves to a new step (queued, a new stage,
 * analysed, failed; progress inside a stage does not count), which is exactly when the memo may have been filled.
 *
 * Each lookup takes the file's stamp (size and last-modified time, on [dispatcher]) and asks the memo with it, so an id
 * remembered for a file that has changed since is a miss. When the provider reports no stamp the memo is asked as it
 * is. A newer status cancels a lookup still running, and cancelling the collection cancels everything.
 */
class StoredDjTrackAnalyses(
    private val status: DjAnalysisStatusSource,
    documents: AudioDocuments,
    index: AudioIdIndex,
    private val store: DjAnalysisStore,
    private val edits: DjGridEditStore,
    dispatcher: CoroutineDispatcher,
) : DjTrackAnalyses {

    private val ids = TrackAudioIds(documents, index, dispatcher)

    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    override fun analysisOf(uri: String): Flow<DjAnalysis?> = ids.follow(status, uri)
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(store.observe(id), edits.observe(id)) { analysis, edit -> analysis?.withGridEdit(edit) }
            }
        }
}
