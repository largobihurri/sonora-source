package io.sonora.core.watchsync

/** The numbers both apps must agree on. */
object SyncLimits {
    /** Bumped when a message or frame changes in a way an older peer cannot read. */
    const val PROTOCOL_VERSION = 1

    /** A control frame carries one JSON message; the biggest is one page of the watch's song list. */
    const val MAX_CONTROL_FRAME_BYTES = 8 * 1024 * 1024

    /**
     * The watch lists what it holds in pages of about this many bytes of JSON, so a library of any size stays far below
     * [MAX_CONTROL_FRAME_BYTES] and the phone never waits for one huge frame.
     */
    const val CONTENTS_PAGE_BYTES = 1024 * 1024

    /** A data frame carries a slice of a song. */
    const val MAX_DATA_FRAME_BYTES = 1024 * 1024

    /** The slice size the sender writes. */
    const val DATA_CHUNK_BYTES = 32 * 1024

    /** Free space the watch keeps for itself (its system, a download in progress, logs): songs never take it. */
    const val MIN_FREE_RESERVE_BYTES = 256L * 1024 * 1024

    /** The longest text a manifest entry may carry in one field. */
    const val MAX_TEXT_CHARS = 1000
}
