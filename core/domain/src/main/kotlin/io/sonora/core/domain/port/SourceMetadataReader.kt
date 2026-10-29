package io.sonora.core.domain.port

import io.sonora.core.domain.conversion.TrackMetadata

/** Reads the tags and front cover a conversion copies from its source. */
fun interface SourceMetadataReader {
    /**
     * The metadata of [sourceUri]; `null` when its tags cannot be read (unsupported or damaged tag, file gone).
     * Never throws except for cancellation: a conversion goes ahead without tags rather than failing.
     */
    suspend fun read(sourceUri: String): TrackMetadata?
}
