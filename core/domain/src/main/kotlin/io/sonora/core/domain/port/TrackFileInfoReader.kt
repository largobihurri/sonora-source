package io.sonora.core.domain.port

import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackFileInfo

/** Reads a track's file details on demand. Main-safe: the work runs off the main thread. */
interface TrackFileInfoReader {
    /**
     * Location, file, audio stream and tag details of [track]'s file. Unreadable tags are reported as
     * [TrackFileInfo.tags] `null`, not as a failure; throws when the file itself cannot be opened (gone, access
     * revoked, I/O error).
     */
    suspend fun read(track: Track): TrackFileInfo
}
