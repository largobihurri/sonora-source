package io.sonora.core.domain.port

import io.sonora.core.domain.lyrics.LocalLyrics
import io.sonora.core.domain.model.Track

/** Lyrics that travel with the song: a sidecar `.lrc` file next to it, then its embedded tags. No network. */
fun interface LocalLyricsSource {
    /** The song's own lyrics; `null` when it has none or they cannot be read now. Main-safe. */
    suspend fun read(track: Track): LocalLyrics?
}

/** What an online lyrics search sends: only the song's names and length. */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String,
    /** Rounded track length; `null` when unknown. */
    val durationSec: Int?,
)

/** One online lyrics record as the service returns it: LRC text, plain text, or a note that the song is instrumental. */
data class OnlineLyrics(val syncedLrc: String?, val plainText: String?, val instrumental: Boolean)

/** An online lyrics service, asked only when the user searches a song. */
fun interface OnlineLyricsSource {
    /** The best record for [query]; `null` when the service has none. Throws when the service cannot be reached. */
    suspend fun find(query: LyricsQuery): OnlineLyrics?
}

/** The result of an online search, kept on the device per song. */
sealed interface CachedLyrics {
    data class Found(val lyrics: OnlineLyrics) : CachedLyrics

    data object NotFound : CachedLyrics
}

/** Online search results kept on the device, by track URI, so a song is never searched twice by itself. */
interface LyricsCache {
    suspend fun get(trackUri: String): CachedLyrics?

    suspend fun put(trackUri: String, entry: CachedLyrics)

    suspend fun remove(trackUri: String)
}
