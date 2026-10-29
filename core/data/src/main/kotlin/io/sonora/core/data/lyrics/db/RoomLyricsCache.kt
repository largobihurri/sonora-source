package io.sonora.core.data.lyrics.db

import io.sonora.core.domain.port.CachedLyrics
import io.sonora.core.domain.port.LyricsCache
import io.sonora.core.domain.port.OnlineLyrics
import javax.inject.Inject

/** [LyricsCache] in the [LyricsDatabase]. */
class RoomLyricsCache @Inject constructor(private val dao: LyricsDao) : LyricsCache {

    override suspend fun get(trackUri: String): CachedLyrics? = dao.get(trackUri)?.let {
        if (it.found) CachedLyrics.Found(OnlineLyrics(it.syncedLyrics, it.plainLyrics, it.instrumental)) else CachedLyrics.NotFound
    }

    override suspend fun put(trackUri: String, entry: CachedLyrics) = dao.put(
        when (entry) {
            is CachedLyrics.Found -> DownloadedLyricsEntity(
                trackUri = trackUri,
                found = true,
                syncedLyrics = entry.lyrics.syncedLrc,
                plainLyrics = entry.lyrics.plainText,
                instrumental = entry.lyrics.instrumental,
            )
            CachedLyrics.NotFound -> DownloadedLyricsEntity(trackUri, found = false, null, null, instrumental = false)
        },
    )

    override suspend fun remove(trackUri: String) = dao.remove(trackUri)
}
