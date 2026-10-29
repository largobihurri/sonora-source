package io.sonora.core.domain.library.smart

import io.sonora.core.domain.model.Track
import java.time.Clock
import kotlinx.coroutines.flow.Flow

/** The smart playlists listed with Favourites in Library > Playlists: songs chosen by a rule, kept live. */
enum class SmartPlaylist { RECENTLY_ADDED, MOST_PLAYED, NEVER_PLAYED }

/** What each smart playlist holds. */
object SmartPlaylistRules {
    /** Recently Added holds the songs added in this many days up to now. */
    const val RECENTLY_ADDED_DAYS = 30

    /** Most Played holds at most this many songs. */
    const val MOST_PLAYED_LIMIT = 50

    /** The earliest date added, seconds since epoch and inclusive, Recently Added keeps at [nowMs]. */
    fun recentlyAddedSinceSec(nowMs: Long): Long = Math.floorDiv(nowMs, MS_PER_SECOND) - RECENTLY_ADDED_DAYS * SECONDS_PER_DAY

    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_DAY = 86_400L
}

/**
 * The songs of each smart playlist, in its order, hidden songs (and songs of hidden albums and artists) left out.
 * Flows re-emit when the library, the hidden items or the play counts change.
 */
interface SmartPlaylistRepository {
    /** Songs added at or after [sinceSec] (seconds since epoch), newest first. */
    fun recentlyAdded(sinceSec: Long): Flow<List<Track>>

    /** Up to [limit] songs played at least once: most plays first, ties by the latest play. */
    fun mostPlayed(limit: Int): Flow<List<Track>>

    /** Songs never played, newest added first. */
    fun neverPlayed(): Flow<List<Track>>
}

/**
 * The live songs of one [SmartPlaylist], by [SmartPlaylistRules]. Recently Added takes its cutoff from [clock] when
 * the flow is created: a page left open still shows songs added since, and drops songs that turn older than the
 * cutoff only when it is opened again.
 */
class ObserveSmartPlaylist(
    private val repository: SmartPlaylistRepository,
    private val clock: Clock,
) {
    operator fun invoke(playlist: SmartPlaylist): Flow<List<Track>> = when (playlist) {
        SmartPlaylist.RECENTLY_ADDED -> repository.recentlyAdded(SmartPlaylistRules.recentlyAddedSinceSec(clock.millis()))
        SmartPlaylist.MOST_PLAYED -> repository.mostPlayed(SmartPlaylistRules.MOST_PLAYED_LIMIT)
        SmartPlaylist.NEVER_PLAYED -> repository.neverPlayed()
    }
}
