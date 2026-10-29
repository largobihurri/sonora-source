package io.sonora.feature.cdjexport.export

import io.sonora.core.djmodel.DjTrackAnalyses
import io.sonora.core.djmodel.DjTrackCues
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Where the export finds a track's DJ data; a track without any is exported as before. */
fun interface DjExportSource {

    /** The DJ analysis and cues of the library track at [sourceUri], or `null` when it has no analysis (or it cannot be read). */
    suspend fun dataFor(sourceUri: String): DjTrackExport?

    companion object {
        /** No DJ data for any track. */
        val NONE: DjExportSource = DjExportSource { null }
    }
}

/**
 * The DJ data the app has stored for a track, found by the track's uri like the DJ screens do: the stored analysis
 * with the user's grid edit ([DjTrackAnalyses]) and the cues ([DjTrackCues]). It never identifies (hashes) a file, so a
 * track the DJ scheduler has not analysed yet has no data and exports as before. A lookup that fails or takes more than
 * [timeoutMs] counts as no data: DJ data is never worth a track.
 */
class StoredDjExportSource @Inject constructor(
    private val analyses: DjTrackAnalyses,
    private val cues: DjTrackCues,
) : DjExportSource {

    override suspend fun dataFor(sourceUri: String): DjTrackExport? = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            analyses.analysisOf(sourceUri).first()?.let { DjTrackExport(it, cues.cuesOf(sourceUri).first()) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
