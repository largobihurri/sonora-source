package io.sonora.core.djengine.identity

import io.sonora.core.djmodel.ByteSource

/**
 * Knows one audio container and finds where its audio payload lies, so the identity hash skips tag blocks and other
 * metadata: retagging, renaming or moving a file must not change its [io.sonora.core.djmodel.AudioId].
 */
interface PayloadWalker {

    /** Short name of the container for logs and tests, e.g. `mp3`, `flac`. */
    val containerTag: String

    /** Whether the file whose first bytes are [prefix] looks like this container (a cheap check, no full parse). */
    fun matches(prefix: ByteArray): Boolean

    /**
     * The byte ranges of [source] that hold the audio payload, in file order, or `null` when the container is
     * malformed (the identifier then falls back to hashing the whole file).
     */
    fun payloadRanges(source: ByteSource): List<LongRange>?
}
