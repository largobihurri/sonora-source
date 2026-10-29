package io.sonora.player.wear.sync

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Refreshes the song list after sync sessions changed the music folder, however the sessions overlap. Every change bumps a
 * counter; a refresh covers the changes counted before it started. So a change is never lost to another session's refresh,
 * refreshes run one at a time (two scans could otherwise publish the older result last), and a refresh that failed is
 * tried again at the next session end because it did not mark its changes as covered.
 *
 * @param refresh Rescans the folder.
 */
class LibraryRefresher(private val refresh: suspend () -> Unit) {
    private val changes = AtomicLong(0)
    private val lock = Mutex()
    private var covered = 0L

    /** The music folder changed (a song arrived or was removed). Safe from any thread. */
    fun changed() {
        changes.incrementAndGet()
    }

    /** Refreshes unless every change so far was already covered by an earlier refresh. @throws Exception whatever [refresh] throws. */
    suspend fun refreshIfChanged() = lock.withLock {
        val seen = changes.get()
        if (seen > covered) {
            refresh()
            covered = seen
        }
    }
}
