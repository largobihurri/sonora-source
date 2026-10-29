package io.sonora.core.domain.lyrics

/** Where a song's lyrics came from. */
enum class LyricsOrigin {
    /** A `.lrc` file with the song's name in its folder. */
    SIDECAR,

    /** The song's own tags. */
    EMBEDDED,

    /** Downloaded from LRCLIB at the user's request and kept on the device. */
    DOWNLOADED,
}

/** Lyrics found with the song itself ([LyricsOrigin.SIDECAR] or [LyricsOrigin.EMBEDDED]). */
data class LocalLyrics(val lyrics: Lyrics, val origin: LyricsOrigin)
