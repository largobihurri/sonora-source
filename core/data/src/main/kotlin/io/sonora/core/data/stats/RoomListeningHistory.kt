package io.sonora.core.data.stats

import io.sonora.core.domain.stats.Listening
import io.sonora.core.domain.stats.ListeningHistory
import io.sonora.core.domain.stats.TrackListening
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** [ListeningHistory] in the statistics database (`listening`). */
@Singleton
class RoomListeningHistory @Inject constructor(
    private val dao: ListeningDao,
) : ListeningHistory {

    override suspend fun record(listening: Listening) {
        if (listening.trackUri.isBlank()) return
        dao.record(
            ListeningEntity(listening.id, listening.trackUri, listening.startedAtMs, listening.listenedMs, listening.counted),
        )
    }

    override fun perTrack(sinceMs: Long?): Flow<List<TrackListening>> =
        dao.perTrack(sinceMs ?: Long.MIN_VALUE).distinctUntilChanged()

    override fun firstStartedAtMs(): Flow<Long?> = dao.firstStartedAtMs().distinctUntilChanged()

    override suspend fun startedBetween(fromMs: Long, toMs: Long, limit: Int, offset: Int): List<Listening> =
        dao.startedBetween(fromMs, toMs, limit, offset).map {
            Listening(it.id, it.trackUri, it.startedAtMs, it.listenedMs, it.counted)
        }

    override suspend fun countStartedBetween(fromMs: Long, toMs: Long): Int = dao.countStartedBetween(fromMs, toMs)
}
