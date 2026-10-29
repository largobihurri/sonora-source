package io.sonora.core.designsystem.motion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The motion of one committed swipe; the host animates its own content offset. */
interface SwipeMotion {
    /** Slides the old content off, in the swipe's direction. */
    suspend fun slideOut()

    /** Brings the new content in from the opposite side. */
    suspend fun slideIn()

    /** Puts the content back in place without motion. */
    suspend fun reset()
}

/**
 * Runs committed item swipes (the mini player's track change) one at a time on [scope]: slide the old item out, run
 * the change, wait for the item key to change (at most [newItemWaitMillis], as the key may never change, e.g. when
 * "previous" restarts the same track), then slide the new item in. With reduced motion it changes and resets only.
 */
class SwipeCommit(private val scope: CoroutineScope, private val newItemWaitMillis: Long = NEW_ITEM_WAIT_MILLIS) {

    /** A committed swipe is still sliding the old item out or the new one in; new swipes are ignored meanwhile. */
    var running: Boolean = false
        private set

    /**
     * Commits a swipe that runs [change]; [itemKeys] emits the current item's key (its first value is the key before
     * the change). Returns its job, or `null` when ignored because another one is running.
     */
    fun commit(change: () -> Unit, itemKeys: Flow<Long?>, motion: SwipeMotion, reducedMotion: Boolean): Job? {
        if (running) return null
        running = true
        return scope.launch {
            try {
                if (reducedMotion) {
                    change()
                    motion.reset()
                    return@launch
                }
                motion.slideOut()
                val before = itemKeys.first()
                change()
                withTimeoutOrNull(newItemWaitMillis) { itemKeys.first { it != before } }
                motion.slideIn()
            } finally {
                running = false
            }
        }
    }

    companion object {
        /** How long a committed swipe waits for the new item before sliding it in anyway. */
        const val NEW_ITEM_WAIT_MILLIS = 400L
    }
}
