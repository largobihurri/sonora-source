package io.sonora.core.watchsync.sender

/**
 * How big a song's Ogg Opus file will be, to check the watch's room before encoding anything. Variable bitrate stays near
 * the target, so this allows 10% above it, plus room for the cover art in the tags. The real size (known once encoded) is
 * what the watch checks again.
 */
object OpusSizeEstimate {
    const val DEFAULT_BITRATE_BPS = 192_000
    const val COVER_ALLOWANCE_BYTES = 64L * 1024

    fun bytesFor(durationMs: Long, bitrateBps: Int = DEFAULT_BITRATE_BPS): Long {
        val audio = maxOf(0L, durationMs) * bitrateBps / 8_000
        return audio * 11 / 10 + COVER_ALLOWANCE_BYTES
    }
}
