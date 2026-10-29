package io.sonora.player.wear.library

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LoadedTracksTest {
    private val one = WatchTrack("1.opus", "file:///1.opus", "One", null, null, null, null)

    private class CountingRepository(private val onDisk: List<WatchTrack>, initial: List<WatchTrack>?) : TrackRepository {
        private val state = MutableStateFlow(initial)
        var refreshes = 0
            private set
        override val tracks: StateFlow<List<WatchTrack>?> = state
        override suspend fun refresh() {
            refreshes++
            state.value = onDisk
        }
    }

    @Test
    fun `a list that was never loaded is loaded first`() = runBlocking<Unit> {
        val repository = CountingRepository(onDisk = listOf(one), initial = null)

        assertThat(repository.loaded()).containsExactly(one)
        assertThat(repository.refreshes).isEqualTo(1)
    }

    @Test
    fun `a loaded list is not scanned again`() = runBlocking<Unit> {
        val repository = CountingRepository(onDisk = emptyList(), initial = listOf(one))

        assertThat(repository.loaded()).containsExactly(one)
        assertThat(repository.refreshes).isEqualTo(0)
    }
}
