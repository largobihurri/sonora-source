package io.sonora.core.domain.library.smart

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.Track
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ObserveSmartPlaylistTest {

    private class RecordingRepository : SmartPlaylistRepository {
        val calls = mutableListOf<String>()
        override fun recentlyAdded(sinceSec: Long): Flow<List<Track>> = record("recentlyAdded:$sinceSec")
        override fun mostPlayed(limit: Int): Flow<List<Track>> = record("mostPlayed:$limit")
        override fun neverPlayed(): Flow<List<Track>> = record("neverPlayed")
        private fun record(call: String): Flow<List<Track>> {
            calls += call
            return flowOf(emptyList())
        }
    }

    private val repository = RecordingRepository()

    private fun observe(nowMs: Long) = ObserveSmartPlaylist(repository, Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC))

    @Test
    fun `recently added keeps 30 days, the song added exactly 30 days ago included`() {
        val nowSec = 1_800_000_000L

        assertThat(SmartPlaylistRules.RECENTLY_ADDED_DAYS).isEqualTo(30)
        assertThat(SmartPlaylistRules.recentlyAddedSinceSec(nowSec * 1_000)).isEqualTo(nowSec - 30 * 86_400)
    }

    @Test
    fun `the recently added cutoff ignores the milliseconds of now`() {
        val nowSec = 1_800_000_000L

        assertThat(SmartPlaylistRules.recentlyAddedSinceSec(nowSec * 1_000 + 999)).isEqualTo(nowSec - 30 * 86_400)
    }

    @Test
    fun `recently added asks for the songs added since the cutoff of the clock's now`() = runBlocking<Unit> {
        observe(nowMs = 1_800_000_000_500L)(SmartPlaylist.RECENTLY_ADDED).first()

        assertThat(repository.calls).containsExactly("recentlyAdded:${1_800_000_000L - 30 * 86_400}")
    }

    @Test
    fun `most played asks for the top 50 songs`() = runBlocking<Unit> {
        observe(nowMs = 0)(SmartPlaylist.MOST_PLAYED).first()

        assertThat(SmartPlaylistRules.MOST_PLAYED_LIMIT).isEqualTo(50)
        assertThat(repository.calls).containsExactly("mostPlayed:50")
    }

    @Test
    fun `never played asks for the songs with no plays`() = runBlocking<Unit> {
        observe(nowMs = 0)(SmartPlaylist.NEVER_PLAYED).first()

        assertThat(repository.calls).containsExactly("neverPlayed")
    }
}
