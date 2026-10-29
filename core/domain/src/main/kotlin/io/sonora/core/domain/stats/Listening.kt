package io.sonora.core.domain.stats

import kotlinx.coroutines.flow.Flow

/**
 * One playback of a song as heard so far: [listenedMs] of actual playing (pauses, buffering and seeks excluded) since
 * it first played at [startedAtMs] (milliseconds since epoch). [counted] once it was heard long enough to count as a
 * play. A playback is saved more than once while it goes on, always under the same [id], each time with more heard.
 */
data class Listening(
    val id: String,
    val trackUri: String,
    val startedAtMs: Long,
    val listenedMs: Long,
    val counted: Boolean,
)

/** What one song added up to over a time range: its counted plays and the time heard. */
data class TrackListening(val trackUri: String, val plays: Int, val listenedMs: Long)

/**
 * Listening recorded per playback, from the version that started recording it on (older play counts have no times;
 * see [io.sonora.core.domain.library.plays.PlayHistoryRepository]).
 */
interface ListeningHistory {
    /** Saves [listening], replacing what was saved for its playback only if it heard more or became counted. */
    suspend fun record(listening: Listening)

    /** Per song, the playbacks that started at or after [sinceMs] (every playback when `null`); re-emits on change. */
    fun perTrack(sinceMs: Long?): Flow<List<TrackListening>>

    /** When the first recorded playback started, `null` before any; statistics with times start there. */
    fun firstStartedAtMs(): Flow<Long?>

    /**
     * The playbacks that started within [fromMs]..[toMs] (both inclusive), oldest first (ties by id), [limit] of them
     * from [offset]. The log is kept until the user clears the statistics.
     */
    suspend fun startedBetween(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<Listening>

    /** How many playbacks started within [fromMs]..[toMs] (both inclusive). */
    suspend fun countStartedBetween(fromMs: Long, toMs: Long): Int
}
