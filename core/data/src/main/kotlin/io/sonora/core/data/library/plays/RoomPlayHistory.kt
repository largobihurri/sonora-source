package io.sonora.core.data.library.plays

import io.sonora.core.data.library.db.PlayCountDao
import io.sonora.core.domain.library.plays.PlayHistoryRepository
import io.sonora.core.domain.library.plays.TrackPlays
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** [PlayHistoryRepository] in the library database (`play_counts`). */
@Singleton
class RoomPlayHistory @Inject constructor(
    private val dao: PlayCountDao,
) : PlayHistoryRepository {

    override suspend fun recordPlay(trackUri: String, playedAtMs: Long) {
        if (trackUri.isBlank()) return
        dao.recordPlay(trackUri, playedAtMs)
    }

    override fun plays(): Flow<Map<String, TrackPlays>> =
        dao.all().map { rows -> rows.associate { it.trackUri to TrackPlays(it.playCount, it.lastPlayedMs) } }.distinctUntilChanged()
}
