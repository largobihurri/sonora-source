package io.sonora.player.wear.remote

import io.sonora.core.watchsync.remote.RemoteLimits
import io.sonora.core.watchsync.remote.RemoteState

/** What the Phone screens show of the connection to the phone. */
sealed interface RemoteUiState {
    /** No phone with Sonora is reachable (or it stopped answering): keep it near, Bluetooth on, Sonora installed. */
    data object NoPhone : RemoteUiState

    /** A phone is there and the first picture of its playback is on the way. */
    data object Connecting : RemoteUiState

    /** The phone's playback as it was [receivedAt] (the watch's clock, in ms). */
    data class Ready(val state: RemoteState, val receivedAt: Long) : RemoteUiState
}

/**
 * Turns "a phone was found", "a state arrived" and the passing of time into a [RemoteUiState]. A state older than [staleMs] is
 * not shown (the phone renews it far more often), and a phone that stays silent that long counts as gone. Pure: the caller
 * supplies the time.
 */
class RemoteConnectionTracker(private val staleMs: Long = RemoteLimits.STALE_MS) {
    private var phoneFound = false
    private var state: RemoteState? = null
    private var receivedAt = 0L
    private var lastSign = 0L

    /** The remote opens: nothing is known yet. */
    fun start(now: Long) {
        phoneFound = false
        state = null
        lastSign = now
    }

    /** The result of the latest look for the phone. A phone that comes back starts afresh; one that is gone takes its state with it. */
    fun onPhone(found: Boolean, now: Long) {
        if (found && !phoneFound) lastSign = now
        if (!found) state = null
        phoneFound = found
    }

    fun onState(state: RemoteState, now: Long) {
        if (!phoneFound) return
        this.state = state
        receivedAt = now
        lastSign = now
    }

    fun ui(now: Long): RemoteUiState {
        if (!phoneFound) return RemoteUiState.NoPhone
        state?.let { if (now - receivedAt <= staleMs) return RemoteUiState.Ready(it, receivedAt) }
        return if (now - lastSign <= staleMs) RemoteUiState.Connecting else RemoteUiState.NoPhone
    }
}
