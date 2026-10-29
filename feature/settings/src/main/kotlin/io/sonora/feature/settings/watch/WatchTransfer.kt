package io.sonora.feature.settings.watch

import io.sonora.core.watchport.WatchBusyException
import io.sonora.core.watchport.WatchSyncFailure
import io.sonora.core.watchport.WatchSyncState
import io.sonora.core.watchsync.ProtocolException
import io.sonora.core.watchsync.WatchNotFoundException
import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.sender.SendProgress
import io.sonora.core.watchsync.sender.SendProgressMath

/** The song a send is working on. [songFraction] is how far the whole song is, encoding and sending together. */
data class CurrentSong(
    val title: String,
    val number: Int,
    val count: Int,
    val phase: SendProgress.Phase,
    val phaseFraction: Float,
    val songFraction: Float,
)

/** A song that did not reach the watch, and why. */
data class FailedSong(val title: String, val reason: SyncError)

/** What the Watch page shows about the controller's [WatchSyncState]. */
sealed interface WatchTransfer {
    data object Idle : WatchTransfer

    /** [song] is `null` while the phone connects and checks the watch's room. */
    data class Running(val songCount: Int, val song: CurrentSong?, val overallFraction: Float) : WatchTransfer

    /** The last send ended; [failed] lists the songs that did not make it. */
    data class Done(
        val sentCount: Int,
        val alreadyThereCount: Int,
        val failed: List<FailedSong>,
        val transferredBytes: Long,
        val bytesPerSecond: Long?,
    ) : WatchTransfer

    /** The last send stopped before it could finish. */
    data class Failed(val failure: WatchSyncFailure) : WatchTransfer
}

/** From the controller's state to what the page shows. Pure. */
object WatchTransfers {
    fun of(state: WatchSyncState): WatchTransfer = when (state) {
        WatchSyncState.Idle -> WatchTransfer.Idle
        is WatchSyncState.Running -> state.progress?.let { progress ->
            WatchTransfer.Running(
                songCount = state.songCount,
                song = CurrentSong(
                    title = progress.title,
                    number = progress.trackNumber,
                    count = progress.trackCount,
                    phase = progress.phase,
                    phaseFraction = progress.phaseFraction.coerceIn(0f, 1f),
                    songFraction = SendProgressMath.songFraction(progress.phase, progress.phaseFraction),
                ),
                overallFraction = progress.overallFraction.coerceIn(0f, 1f),
            )
        } ?: WatchTransfer.Running(state.songCount, song = null, overallFraction = 0f)
        is WatchSyncState.Finished -> WatchTransfer.Done(
            sentCount = state.report.sent.size,
            alreadyThereCount = state.report.skipped.size,
            failed = state.report.failed.map { FailedSong(it.title, it.reason) },
            transferredBytes = state.report.transferredBytes,
            bytesPerSecond = state.report.bytesPerSecond?.toLong(),
        )
        is WatchSyncState.Failed -> WatchTransfer.Failed(state.failure)
    }
}

/** Whether the phone can reach the watch, as the page's first line. */
enum class WatchLink {
    /** Reading the watch. */
    CHECKING,

    /** The watch answered. */
    CONNECTED,

    /** A send holds the connection; the watch is being sent to. */
    SENDING,

    /** No watch with Sonora is connected. */
    NO_WATCH,

    /** The watch app speaks another version of the protocol. */
    APP_OUTDATED,

    /** The watch could not be read (the link dropped, or something unexpected). */
    UNREACHABLE,
}

object WatchLinks {
    /** What a failed read of the watch says about the connection. */
    fun of(error: Throwable): WatchLink = when (error) {
        is WatchBusyException -> WatchLink.SENDING
        is WatchNotFoundException -> WatchLink.NO_WATCH
        is ProtocolException -> WatchLink.APP_OUTDATED
        else -> WatchLink.UNREACHABLE
    }
}
