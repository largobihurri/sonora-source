package io.sonora.core.domain.port

import io.sonora.core.domain.library.TrackTags

/** A converted file as read to play it: its tags, its cached embedded picture and its document details. */
data class ConvertedFile(
    val tags: TrackTags,
    /** Cached copy of the file's embedded picture, if it has one. */
    val embeddedArtworkUri: String?,
    val mimeType: String,
    val sizeBytes: Long,
)

/** Opens a converter output to play it. Main-safe: the work runs off the main thread. */
fun interface ConvertedFileReader {
    /**
     * The file at [uri] ([fileName] when the storage reports no name); `null` when it no longer exists or cannot be
     * opened (moved, deleted, access revoked). Unreadable tags give [TrackTags.UNTAGGED], not `null`. Never throws
     * except for cancellation.
     */
    suspend fun read(uri: String, fileName: String): ConvertedFile?
}
