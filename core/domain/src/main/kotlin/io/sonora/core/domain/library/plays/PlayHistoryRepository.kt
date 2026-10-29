package io.sonora.core.domain.library.plays

import kotlinx.coroutines.flow.Flow

/** How often a song was played and when last, milliseconds since epoch. */
data class TrackPlays(val count: Int, val lastPlayedMs: Long)

/**
 * Play counts per song, keyed by the song's library key (its track uri). Playback records a play once a song was
 * heard long enough to count (half of it or 4 minutes, whichever comes first); conversions and exports never do.
 */
interface PlayHistoryRepository {
    /** Adds one play of the song at [trackUri], played at [playedAtMs]. A blank key is ignored. */
    suspend fun recordPlay(trackUri: String, playedAtMs: Long)

    /** Every song played at least once, by key; re-emits after each recorded play. */
    fun plays(): Flow<Map<String, TrackPlays>>
}
