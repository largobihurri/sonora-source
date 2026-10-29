package io.sonora.core.api

/**
 * A page of rows. [total] counts every row of the query, [limit] is the page size actually used (the caller's, held
 * to [ApiLimits.MAX_PAGE_SIZE]) and [offset] where the page starts.
 */
data class Page<T>(val rows: List<T>, val total: Int, val limit: Int, val offset: Int) {
    val hasMore: Boolean get() = offset + rows.size < total
}

/**
 * A song as the API shows it. Library ids and tags only: never the file's URI, path, size or artwork location, so a
 * caller learns nothing about where the library lives.
 */
data class TrackRow(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
)

data class AlbumRow(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val year: Int?,
    val trackCount: Int,
)

data class ArtistRow(val id: Long, val name: String, val albumCount: Int, val trackCount: Int)

/** [trackCount] counts every entry, including songs no longer in the library. */
data class PlaylistRow(val id: Long, val name: String, val description: String, val trackCount: Int)

/** One entry of the play queue; [index] is what `queue_remove`, `queue_move` and `queue_skip_to` take. */
data class QueueRow(val index: Int, val queueItemId: Long, val isCurrent: Boolean, val track: TrackRow)

/** The running sleep timer: [mode] is `minutes`, `end_of_song` or `end_of_album`; [remainingMs] is null when unknown. */
data class SleepTimerRow(val mode: String, val remainingMs: Long?)

/** Everything a caller needs to know at once: the player and the settings the API can change. */
data class StateRow(
    val apiVersion: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    /** `off`, `all` or `one`. */
    val repeat: String,
    val currentIndex: Int,
    val queueSize: Int,
    val currentTrack: TrackRow?,
    val sleepTimer: SleepTimerRow?,
    val crossfadeSeconds: Int,
    /** Playback speed as a factor: 1.0 is normal. */
    val speed: Double,
    val djMode: Boolean,
)

/** The outcome of a query. The provider adapter turns each rows type into a `Cursor`, and a failure into an error. */
sealed interface ApiQueryResult {
    data class Failed(val error: ApiError) : ApiQueryResult
    data class State(val state: StateRow) : ApiQueryResult
    data class Queue(val page: Page<QueueRow>) : ApiQueryResult
    data class Tracks(val page: Page<TrackRow>) : ApiQueryResult
    data class Albums(val page: Page<AlbumRow>) : ApiQueryResult
    data class Artists(val page: Page<ArtistRow>) : ApiQueryResult
    data class Playlists(val page: Page<PlaylistRow>) : ApiQueryResult
}
