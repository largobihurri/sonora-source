package io.sonora.player.wear.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The logic of [WatchReceiveService], free of Android: keeps one watcher armed per run of the service that calls [onIdle]
 * once no sync session is running. A watcher that already fired is not armed any more, so the next [onStart] (another
 * transfer, delivered to the same service instance before Android destroys it) arms a fresh one instead of leaving the
 * foreground service up until its timeout.
 *
 * Used from one thread (the main thread).
 *
 * @param activeSessions How many sessions are running.
 * @param onIdle Lets go of the foreground state; called once per armed watcher.
 */
class KeepAliveController(
    private val scope: CoroutineScope,
    private val activeSessions: StateFlow<Int>,
    private val onIdle: () -> Unit,
) {
    private var watcher: Job? = null

    /** The service was started (again): make sure a watcher is waiting for the sessions to end. */
    fun onStart() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            activeSessions.first { it == 0 }
            onIdle()
        }
    }
}
