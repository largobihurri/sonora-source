package io.sonora.core.watchsync.sender

/**
 * A song the user chose to send: what the phone knows about it before encoding. [id] is [io.sonora.core.watchsync.TrackIds.of]
 * of the source and the encoding profile; [sourceUri] is for the [TrackEncoder] to find the original.
 */
data class SendCandidate(
    val id: String,
    val sourceUri: String,
    val title: String,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long,
    /** Changes when the original does (its size); an encoding kept from an interrupted send is only reused for the same one. */
    val sourceVersion: Long = 0L,
)
