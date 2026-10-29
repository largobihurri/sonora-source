package io.sonora.core.watchsync.sender

import io.sonora.core.watchsync.SyncLimits

/**
 * @property maxAttempts Connections to try for one operation before giving up on a dropping or unreachable watch.
 * @property retryDelaysMs The wait before the second, third... attempt; the last value repeats.
 * @property replyTimeoutMs How long to wait for the watch to answer before treating the connection as dead.
 * @property trackRetries How many times a song whose checksum failed is sent again on the same connection.
 * @property chunkBytes The size of the slices a song is written in.
 */
data class SenderConfig(
    val maxAttempts: Int = 4,
    val retryDelaysMs: List<Long> = listOf(1_000L, 3_000L, 10_000L),
    val replyTimeoutMs: Long = 60_000L,
    val trackRetries: Int = 2,
    val chunkBytes: Int = SyncLimits.DATA_CHUNK_BYTES,
) {
    init {
        require(maxAttempts >= 1) { "At least one attempt" }
        require(chunkBytes in 1..SyncLimits.MAX_DATA_FRAME_BYTES) { "Chunk size $chunkBytes" }
    }

    /** The wait before attempt number [attempt] + 1, after attempt [attempt] failed. */
    fun retryDelayMs(attempt: Int): Long = retryDelaysMs.getOrElse(attempt - 1) { retryDelaysMs.lastOrNull() ?: 0L }
}
