package io.sonora.player.wear.kora

import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.core.ProviderBuilder
import io.sonora.player.wear.history.PlayLog
import io.sonora.player.wear.library.WatchTrack

/**
 * `media.history` over the watch's own play log: the songs it played itself, never the phone's.
 *
 * - Every play is listed; `playedMs` is how long it was heard, so a caller can drop short ones.
 * - Retention is the log's: the last 90 days and 5000 plays (see `FilePlayLog`); an older window returns fewer plays.
 * - A play whose song file is gone cannot be named, so it is skipped, and `total` counts only the plays that can be listed.
 * - Ids are the same opaque ids as `media.library`, so no file name or path leaves the app.
 *
 * [log] and [songs] are read on each call and not at registration: the node is installed at every process start, calls
 * are rare. An inverted window never gets here: the contract rejects it when the request is decoded.
 */
internal class WatchHistoryHandlers(
    private val log: () -> PlayLog,
    private val songs: suspend () -> List<WatchTrack>,
) {
    /** Registers every operation of the capability (the node rejects a partial provider at install). */
    fun register(builder: ProviderBuilder<MediaHistory>) = with(builder) {
        on(MediaHistory.ListPlays) { request -> listPlays(request) }
    }

    suspend fun listPlays(request: PlaysRequest): Page<PlayRecord> {
        val offset = request.page.offset
        val plays = log().between(request.fromEpochMs, request.toEpochMs)
        if (plays.isEmpty()) return Page(emptyList(), 0, offset)
        val byId = songs().associateBy { koraTrackId(it.id) }
        val listable = plays.mapNotNull { play ->
            byId[play.trackId]?.let { PlayRecord(trackSummary(it), play.startedAtMs, play.listenedMs) }
        }
        val items = listable.drop(offset).take(minOf(request.page.limit, MAX_PAGE_SIZE))
        return Page(items, listable.size, offset)
    }
}
