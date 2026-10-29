package io.sonora.core.watchport

import io.sonora.core.watchsync.NotEnoughSpaceException
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.WatchNotFoundException
import io.sonora.core.watchsync.sender.SendProgress
import io.sonora.core.watchsync.sender.SendReport
import java.io.IOException

/** Why a send to the watch stopped, in terms a screen can word. */
sealed interface WatchSyncFailure {
    /** No watch with Sonora is connected. */
    data object NoWatch : WatchSyncFailure

    /** The watch app speaks another version of the protocol: update it (or this app). */
    data object WatchAppIncompatible : WatchSyncFailure

    /** The songs do not fit; both numbers are bytes. */
    data class NotEnoughSpace(val neededBytes: Long, val availableBytes: Long) : WatchSyncFailure

    /** The link dropped and could not be taken up again. What reached the watch is kept: send again to resume. */
    data object ConnectionLost : WatchSyncFailure

    /** The album or playlist has no song to send. */
    data object NothingToSend : WatchSyncFailure

    data class Unexpected(val message: String?) : WatchSyncFailure

    companion object {
        fun of(error: Throwable): WatchSyncFailure = when (error) {
            is WatchNotFoundException -> NoWatch
            is ProtocolException -> WatchAppIncompatible
            is NotEnoughSpaceException -> NotEnoughSpace(error.neededBytes, error.availableBytes)
            is IOException -> ConnectionLost
            else -> Unexpected(error.message)
        }
    }
}

/** What the Watch screen shows about the send in progress. */
sealed interface WatchSyncState {
    data object Idle : WatchSyncState

    /**
     * A send is running. [progress] is `null` until the first song starts (connecting and checking the watch's room);
     * [songCount] is the size of the selection.
     */
    data class Running(val progress: SendProgress?, val songCount: Int) : WatchSyncState

    /** The send ended; the report says what reached the watch, what was already there and what failed song by song. */
    data class Finished(val report: SendReport) : WatchSyncState

    data class Failed(val failure: WatchSyncFailure) : WatchSyncState
}

/** A read or removal was refused because a send holds the watch connection. */
class WatchBusyException : Exception("A send to the watch is running")
