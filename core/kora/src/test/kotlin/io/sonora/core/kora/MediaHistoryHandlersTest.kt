package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.media.MediaHistory
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlayRecord
import eus.bihurri.kora.contract.media.PlaysRequest
import eus.bihurri.kora.contract.CborCodec
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.KoraRequest
import eus.bihurri.kora.contract.KoraResponse
import eus.bihurri.kora.core.Caller
import eus.bihurri.kora.core.CallerKind
import eus.bihurri.kora.core.KoraNode
import io.sonora.core.domain.stats.Listening
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Goes through a real [KoraNode], so a handler missing from the registration fails here, not at app start. */
class MediaHistoryHandlersTest {
    private val songs = listOf(track(1, title = "One"), track(2, title = "Two"), track(3, title = "Three"))
    private val library = FakeLibrary(songs)
    private val history = FakeListeningHistory()
    private val node = KoraNode("test.sonora").apply {
        val handlers = MediaHistoryHandlers({ history }, { library })
        provide(MediaHistory) { handlers.register(this) }
    }

    private fun play(id: String, track: Long, at: Long, heard: Long = 1_000, counted: Boolean = true) =
        Listening(id, "content://media/audio/$track", at, heard, counted)

    private suspend fun plays(from: Long, to: Long, limit: Int = 50, offset: Int = 0) =
        node.call(MediaHistory.ListPlays, PlaysRequest(from, to, PageRequest(limit, offset)))

    @Test
    fun `plays in the window come oldest first with the library track and how long they played`() = runBlocking<Unit> {
        history.all = listOf(
            play("b", 2, at = 2_000, heard = 5_000, counted = false),
            play("a", 1, at = 1_000, heard = 200_000),
            play("out", 3, at = 9_000),
        )

        val page = plays(1_000, 2_000)

        assertThat(page.total).isEqualTo(2)
        assertThat(page.items).containsExactly(
            PlayRecord(trackSummary(songs[0]), startedAtEpochMs = 1_000, playedMs = 200_000),
            PlayRecord(trackSummary(songs[1]), startedAtEpochMs = 2_000, playedMs = 5_000),
        ).inOrder()
    }

    @Test
    fun `an id is the library id and nothing leaks a uri or a path`() = runBlocking<Unit> {
        history.all = listOf(play("a", 1, at = 1_000))

        val record = plays(0, 10_000).items.single()

        assertThat(record.track.id).isEqualTo("1")
        assertThat(record.toString()).doesNotContain("content://")
    }

    @Test
    fun `plays of tracks that left the library are skipped and the total agrees`() = runBlocking<Unit> {
        history.all = listOf(
            play("a", 1, at = 1_000),
            play("gone1", 99, at = 2_000),
            play("b", 2, at = 3_000),
            play("gone2", 98, at = 4_000),
            play("c", 3, at = 5_000),
        )

        val page = plays(0, 10_000, limit = 2, offset = 1)

        assertThat(page.total).isEqualTo(3)
        assertThat(page.items.map { it.startedAtEpochMs }).containsExactly(3_000L, 5_000L).inOrder()
        assertThat(page.offset).isEqualTo(1)
    }

    @Test
    fun `pages follow one another without gaps or repeats`() = runBlocking<Unit> {
        history.all = (1..7).map { play("p$it", 1 + (it % 3).toLong(), at = it * 1_000L) }

        val seen = listOf(0, 3, 6).flatMap { plays(0, 99_000, limit = 3, offset = it).items }

        assertThat(seen.map { it.startedAtEpochMs }).containsExactlyElementsIn((1..7).map { it * 1_000L }).inOrder()
    }

    @Test
    fun `a page is capped like the library pages`() = runBlocking<Unit> {
        history.all = (1..MAX_PAGE_SIZE + 30).map { play("p$it", 1, at = it.toLong()) }

        val page = plays(0, 1_000_000, limit = 10_000)

        assertThat(page.items).hasSize(MAX_PAGE_SIZE)
        assertThat(page.total).isEqualTo(MAX_PAGE_SIZE + 30)
    }

    @Test
    fun `a long log is read in bounded batches`() = runBlocking<Unit> {
        history.all = (1..1_200).map { play("p$it", 1, at = it.toLong()) }

        val page = plays(0, 1_000_000, limit = 5, offset = 1_195)

        assertThat(page.items).hasSize(5)
        assertThat(page.total).isEqualTo(1_200)
        assertThat(history.batches).isAtLeast(2)
    }

    @Test
    fun `an empty window, an empty log or a window before the log is an empty page, not an error`() = runBlocking<Unit> {
        assertThat(plays(0, 10).items).isEmpty()
        history.all = listOf(play("a", 1, at = 5_000))

        assertThat(plays(5_001, 6_000).total).isEqualTo(0)
        assertThat(plays(5_000, 5_000).items).hasSize(1)
    }

    @Test
    fun `an offset past the end is an empty page that keeps the total`() = runBlocking<Unit> {
        history.all = listOf(play("a", 1, at = 5_000))

        val page = plays(0, 10_000, offset = 10)

        assertThat(page.items).isEmpty()
        assertThat(page.total).isEqualTo(1)
    }

    /**
     * `PlaysRequest` rejects an inverted window in its own constructor, so none can be built here: the bytes of a valid
     * one (`from = 1`, `to = 2`, each a one-byte CBOR integer right after its key) are swapped to what a misbehaving
     * client would send. The node must answer `invalid_payload` before the handler runs.
     */
    @Test
    fun `a window that ends before it starts is an invalid payload and never reaches the log`() = runBlocking<Unit> {
        history.all = listOf(play("a", 1, at = 1_500))
        val bytes = MediaHistory.ListPlays.encodeInput(CborCodec, PlaysRequest(1, 2))
        bytes[valueIndex(bytes, "fromEpochMs")] = 2
        bytes[valueIndex(bytes, "toEpochMs")] = 1
        val request = KoraRequest("1", MediaHistory.id.value, MediaHistory.version, MediaHistory.ListPlays.name, bytes)

        val response = node.handle(Caller(CallerKind.SELF, "test"), request)

        assertThat(response).isInstanceOf(KoraResponse.Failure::class.java)
        assertThat((response as KoraResponse.Failure).error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
    }

    /** Index of the byte that follows the text [key] in [bytes]. */
    private fun valueIndex(bytes: ByteArray, key: String): Int {
        val needle = key.encodeToByteArray()
        val at = (0..bytes.size - needle.size).first { start -> needle.indices.all { bytes[start + it] == needle[it] } }
        return at + needle.size
    }
}
