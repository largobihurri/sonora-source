package io.sonora.core.domain.lyrics

import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.CachedLyrics
import io.sonora.core.domain.port.LocalLyricsSource
import io.sonora.core.domain.port.LyricsCache
import io.sonora.core.domain.port.LyricsQuery
import io.sonora.core.domain.port.OnlineLyrics
import io.sonora.core.domain.port.OnlineLyricsSource
import kotlin.coroutines.cancellation.CancellationException

/** What is known about a song's lyrics. */
sealed interface LyricsLookup {
    data class Found(val lyrics: Lyrics, val origin: LyricsOrigin) : LyricsLookup

    /** Searched online at the user's request; the service has none. */
    data object NotFound : LyricsLookup

    /** None on the device and never searched online. */
    data object Missing : LyricsLookup
}

/**
 * A song's lyrics: its own (sidecar file, then tags) first, then a previous online search kept on the device. The
 * online service is asked only through [search], which the user triggers for one song; whatever it answers, found or
 * not, is kept so the song is never searched again by itself. A build without an online service ([online] `null`, the
 * Google Play build) offers no search ([canSearchOnline]).
 */
class LyricsRepository(
    private val local: LocalLyricsSource,
    private val cache: LyricsCache,
    private val online: OnlineLyricsSource?,
) {
    /** Whether [search] can ask an online service; without one the lyrics panel offers no search. */
    val canSearchOnline: Boolean get() = online != null

    /** Lyrics on the device for [track]; never goes online. */
    suspend fun load(track: Track): LyricsLookup {
        val own = try {
            local.read(track)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // unreadable files or tags: what was downloaded may still serve
        }
        if (own != null) return LyricsLookup.Found(own.lyrics, own.origin)
        return when (val cached = cache.get(track.uri)) {
            is CachedLyrics.Found -> cached.lyrics.toLyrics()?.let { LyricsLookup.Found(it, LyricsOrigin.DOWNLOADED) }
                ?: LyricsLookup.Missing
            CachedLyrics.NotFound -> LyricsLookup.NotFound
            null -> LyricsLookup.Missing
        }
    }

    /**
     * Searches the online service for [track] and keeps the answer: [LyricsLookup.Found] (downloaded) or
     * [LyricsLookup.NotFound]. Throws, keeping nothing, when the service cannot be reached, and
     * [IllegalStateException] when there is no service ([canSearchOnline]).
     */
    suspend fun search(track: Track): LyricsLookup {
        val online = checkNotNull(online) { "No online lyrics service in this build" }
        val query = LyricsQuery(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = track.durationMs.takeIf { it > 0 }?.let { ((it + 500) / 1_000).toInt() },
        )
        val record = online.find(query)
        val lyrics = record?.toLyrics()
        if (record == null || lyrics == null) {
            cache.put(track.uri, CachedLyrics.NotFound)
            return LyricsLookup.NotFound
        }
        cache.put(track.uri, CachedLyrics.Found(record))
        return LyricsLookup.Found(lyrics, LyricsOrigin.DOWNLOADED)
    }

    /** Forgets what an online search kept for [track]; it can be searched again. */
    suspend fun removeDownloaded(track: Track) = cache.remove(track.uri)

    /** Timed lyrics when the record has usable ones, else its plain text, else instrumental; `null` when empty. */
    private fun OnlineLyrics.toLyrics(): Lyrics? =
        syncedLrc?.let(Lyrics::parse) ?: plainText?.let(Lyrics::parse) ?: Lyrics.Instrumental.takeIf { instrumental }
}
