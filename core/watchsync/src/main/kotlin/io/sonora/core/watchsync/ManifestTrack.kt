package io.sonora.core.watchsync

import kotlinx.serialization.Serializable

/**
 * One song as the phone offers it and the watch lists it: its id, tags, length, and the size and SHA-256 of the encoded
 * file (an Ogg Opus file, the watch's `<id>.opus`). The size and checksum are of the finished file, so the watch can
 * check its room before receiving and verify what it received.
 */
@Serializable
data class ManifestTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha256: String,
) {
    /** Why this entry cannot be accepted, or `null` when it can. Both sides check what they are handed. */
    fun problem(): String? = when {
        !TrackIds.isValid(id) -> "Invalid track id"
        sizeBytes <= 0 -> "Invalid size $sizeBytes"
        !Sha256.isValidHex(sha256) -> "Invalid checksum"
        durationMs < 0 -> "Invalid duration $durationMs"
        listOfNotNull(title, artist, albumArtist, album).any { it.length > SyncLimits.MAX_TEXT_CHARS } -> "Text too long"
        else -> null
    }
}
