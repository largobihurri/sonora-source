package io.sonora.core.watchsync.sender

import io.sonora.core.watchsync.protocol.SyncError
import io.sonora.core.watchsync.protocol.SyncMessage
import io.sonora.core.watchsync.ManifestTrack

/**
 * A song that reached the watch.
 *
 * @property transferredBytes Bytes sent in this run (the file's size minus [resumedFrom]).
 * @property elapsedMs From offering the song to the watch confirming it.
 * @property resumedFrom Where the watch already was when the transfer started: 0 for a fresh song.
 */
data class SentTrack(
    val id: String,
    val title: String,
    val sizeBytes: Long,
    val transferredBytes: Long,
    val elapsedMs: Long,
    val resumedFrom: Long,
)

/** A song that did not make it, and why. Others in the send are unaffected. */
data class FailedTrack(val id: String, val title: String, val reason: SyncError)

/**
 * What a finished send did. A song neither [sent] nor [skipped] is in [failed].
 *
 * @property skipped Songs the watch already held.
 * @property attempts Connections used (more than 1 when one dropped and the send resumed).
 */
data class SendReport(
    val sent: List<SentTrack>,
    val skipped: List<SendCandidate>,
    val failed: List<FailedTrack>,
    val attempts: Int,
) {
    val transferredBytes: Long get() = sent.sumOf { it.transferredBytes }

    /** Average speed over the time songs spent on the wire; `null` when nothing was sent. */
    val bytesPerSecond: Double?
        get() {
            val seconds = sent.sumOf { it.elapsedMs } / 1000.0
            return if (seconds > 0) transferredBytes / seconds else null
        }
}

/** The watch's space and songs, as the phone read them. */
data class WatchState(
    val freeBytes: Long,
    val totalBytes: Long,
    val reserveBytes: Long,
    val tracks: List<ManifestTrack>,
) {
    /** What songs may still use. */
    val usableBytes: Long get() = maxOf(0L, freeBytes - reserveBytes)

    val freeSpace: SyncMessage.FreeSpace get() = SyncMessage.FreeSpace(freeBytes, totalBytes, reserveBytes)
}
