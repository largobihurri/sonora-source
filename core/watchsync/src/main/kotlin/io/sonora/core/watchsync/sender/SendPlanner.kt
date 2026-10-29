package io.sonora.core.watchsync.sender

import io.sonora.core.watchsync.ManifestTrack
import io.sonora.core.watchsync.protocol.SyncMessage

/** Whether the songs fit in the watch's usable space. */
sealed interface SpaceCheck {
    data object Fits : SpaceCheck

    data class NotEnough(val neededBytes: Long, val availableBytes: Long) : SpaceCheck
}

/**
 * What a send has to do.
 *
 * @property toSend Songs to encode and send, in the order asked (each once).
 * @property alreadyOnWatch Songs the watch holds already: nothing to do for them.
 * @property estimatedBytes What [toSend] will take on the watch, estimated before encoding.
 */
data class SendPlan(
    val toSend: List<SendCandidate>,
    val alreadyOnWatch: List<SendCandidate>,
    val estimatedBytes: Long,
    val space: SpaceCheck,
)

/** Pure planning of a send: which songs are new to the watch, and whether they fit. */
object SendPlanner {

    fun plan(candidates: List<SendCandidate>, contents: List<ManifestTrack>, space: SyncMessage.FreeSpace): SendPlan {
        val held = contents.mapTo(HashSet()) { it.id }
        val (already, toSend) = candidates.distinctBy { it.id }.partition { it.id in held }
        val estimate = toSend.sumOf { OpusSizeEstimate.bytesFor(it.durationMs) }
        return SendPlan(toSend, already, estimate, check(estimate, space.usableBytes))
    }

    fun check(neededBytes: Long, usableBytes: Long): SpaceCheck =
        if (neededBytes <= usableBytes) SpaceCheck.Fits else SpaceCheck.NotEnough(neededBytes, usableBytes)
}
