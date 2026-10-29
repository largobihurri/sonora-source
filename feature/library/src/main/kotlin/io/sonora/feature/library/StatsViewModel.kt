package io.sonora.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.plays.PlayHistoryRepository
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.stats.ListeningHistory
import io.sonora.core.domain.stats.ListeningStats
import io.sonora.core.domain.stats.StatsRange
import io.sonora.core.domain.stats.listeningStats
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Library > Statistics: the [range] chosen and what it added up to; times start at [sinceMs] (`null` before any). */
data class StatsUiState(
    val loading: Boolean = true,
    val range: StatsRange = StatsRange.WEEK,
    val stats: ListeningStats = ListeningStats(),
    val sinceMs: Long? = null,
)

/**
 * Listening statistics over a time range, opening on the last week. A range ends when it is chosen ([clock]); the
 * statistics follow new playbacks, play counts and library changes while the screen is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    history: ListeningHistory,
    plays: PlayHistoryRepository,
    library: LibraryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val range = MutableStateFlow(StatsRange.WEEK)

    private val listening = range.flatMapLatest { range ->
        history.perTrack(range.startMs(clock.millis())).map { range to it }
    }

    val state: StateFlow<StatsUiState> = combine(listening, plays.plays(), library.tracks(), history.firstStartedAtMs()) {
            (range, perTrack), counts, tracks, sinceMs ->
        StatsUiState(loading = false, range = range, stats = listeningStats(range, perTrack, counts, tracks), sinceMs = sinceMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun select(range: StatsRange) {
        this.range.value = range
    }
}
