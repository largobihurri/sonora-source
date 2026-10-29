package io.sonora.player.wear.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.core.KoraNode
import io.sonora.player.wear.history.PlayLog
import io.sonora.player.wear.history.PlayLogEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Goes through a real [KoraNode], so a handler missing from the registration fails here, not at app start. */
class WatchHistoryHandlersTest {
    private var songs = listOf(song("a.opus", title = "Alpha"), song("b.opus", title = "Beta"))
    private val plays = mutableListOf<PlayLogEntry>()
    private var reads = 0
    private val log = object : PlayLog {
        override fun record(entry: PlayLogEntry) = Unit
        override fun between(fromMs: Long, toMs: Long): List<PlayLogEntry> {
            reads++
            return plays.filter { it.startedAtMs in fromMs..toMs }.sortedBy { it.startedAtMs }
        }
    }
    private val node = KoraNode("test.watch").apply {
        val handlers = WatchHistoryHandlers(log = { log }, songs = { songs })
        provide(MediaHistory) { handlers.register(this) }
    }

    private fun play(file: String, at: Long, listened: Long = 60_000) = PlayLogEntry(koraTrackId(file), at, listened)

    private suspend fun list(from: Long, to: Long, page: PageRequest = PageRequest()) =
        node.call(MediaHistory.ListPlays, PlaysRequest(from, to, page))

    @Test
    fun `plays in the window come oldest first with the song and how long it played`() = runBlocking<Unit> {
        plays += listOf(play("b.opus", 300, 5_000), play("a.opus", 100, 60_000), play("a.opus", 900))

        val page = list(100, 300)

        assertThat(page.total).isEqualTo(2)
        assertThat(page.items.map { it.startedAtEpochMs }).containsExactly(100L, 300L).inOrder()
        assertThat(page.items.map { it.playedMs }).containsExactly(60_000L, 5_000L).inOrder()
        assertThat(page.items[0].track).isEqualTo(trackSummary(songs[0]))
    }

    @Test
    fun `an id is the library id and nothing leaks a uri or a path`() = runBlocking<Unit> {
        plays += play("a.opus", 1)

        val json = list(0, 10).items.single().toString()

        assertThat(json).doesNotContain("file:")
        assertThat(json).doesNotContain("a.opus")
    }

    @Test
    fun `plays of songs that left the watch are skipped and the total agrees`() = runBlocking<Unit> {
        plays += listOf(play("a.opus", 1), play("gone.opus", 2), play("b.opus", 3))

        val page = list(0, 10)

        assertThat(page.items.map { it.track.title }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `pages follow one another without gaps or repeats`() = runBlocking<Unit> {
        plays += (1L..5L).map { play("a.opus", it) }

        val first = list(0, 10, PageRequest(offset = 0, limit = 2))
        val second = list(0, 10, PageRequest(offset = 2, limit = 2))
        val third = list(0, 10, PageRequest(offset = 4, limit = 2))

        assertThat((first.items + second.items + third.items).map { it.startedAtEpochMs })
            .containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
        assertThat(third.total).isEqualTo(5)
    }

    @Test
    fun `a page is capped like the library pages`() = runBlocking<Unit> {
        plays += (1L..(MAX_PAGE_SIZE + 50L)).map { play("a.opus", it) }

        assertThat(list(0, Long.MAX_VALUE, PageRequest(limit = 10_000)).items).hasSize(MAX_PAGE_SIZE)
    }

    @Test
    fun `an empty log or window is an empty page, and an offset past the end keeps the total`() = runBlocking<Unit> {
        assertThat(list(0, 10).items).isEmpty()

        plays += play("a.opus", 5)
        assertThat(list(6, 10).total).isEqualTo(0)
        val past = list(0, 10, PageRequest(offset = 50))
        assertThat(past.items).isEmpty()
        assertThat(past.total).isEqualTo(1)
    }

    @Test
    fun `an inverted window is rejected by the contract before it reaches the log`() {
        assertThrows(IllegalArgumentException::class.java) { PlaysRequest(10, 5) }
        assertThat(reads).isEqualTo(0)
    }
}
