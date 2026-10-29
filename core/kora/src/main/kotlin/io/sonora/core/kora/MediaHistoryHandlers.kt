package io.sonora.core.kora

import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.core.ProviderBuilder
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.stats.ListeningHistory
import javax.inject.Inject

/**
 * `media.history` over the statistics log (`stats.db`, one row per playback).
 *
 * - Every playback is listed, counted or not; `playedMs` is how long it was heard, so a caller can drop short ones.
 * - Retention is the statistics history's own: the log is kept until the user clears it, so a window older than that
 *   simply returns fewer plays.
 * - There is no origin: a node reports its own plays only.
 * - Ids are the library ids of the phone node; no URI or path leaves the app. A playback whose track left the library
 *   cannot be named, so it is skipped, and `total` counts only the plays that can be listed.
 *
 * The ports are taken lazily, as in the other handlers.
 */
internal class MediaHistoryHandlers(
    private val history: () -> ListeningHistory,
    private val library: () -> LibraryRepository,
) {
    @Inject
    constructor(history: dagger.Lazy<ListeningHistory>, library: dagger.Lazy<LibraryRepository>) :
        this({ history.get() }, { library.get() })

    /** Registers every operation of the capability (the node rejects a partial provider at install). */
    fun register(builder: ProviderBuilder<MediaHistory>) = with(builder) {
        on(MediaHistory.ListPlays) { request -> listPlays(request) }
    }

    /**
     * The window is read in batches of [BATCH_SIZE] rows, so a long log is never held whole: only the requested page is
     * kept, and the rest is just counted to give an exact `total` once rows without a library track are left out.
     */
    suspend fun listPlays(request: PlaysRequest): Page<PlayRecord> {
        val log = history()
        val limit = minOf(request.page.limit, MAX_PAGE_SIZE)
        val offset = request.page.offset
        val rows = log.countStartedBetween(request.fromEpochMs, request.toEpochMs)
        if (rows == 0) return Page(emptyList(), 0, offset)

        val byUri = library().byUri()
        val items = ArrayList<PlayRecord>(minOf(limit, rows))
        var listable = 0
        var read = 0
        while (read < rows) {
            val batch = log.startedBetween(request.fromEpochMs, request.toEpochMs, BATCH_SIZE, read)
            if (batch.isEmpty()) break
            read += batch.size
            for (play in batch) {
                val track = byUri[play.trackUri] ?: continue
                if (listable >= offset && items.size < limit) {
                    items += PlayRecord(trackSummary(track), play.startedAtMs, play.listenedMs)
                }
                listable++
            }
        }
        return Page(items, listable, offset)
    }

    private companion object {
        const val BATCH_SIZE = 500
    }
}
