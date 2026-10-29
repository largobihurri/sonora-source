package io.sonora.player.wear.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackStatus
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class KoraMappingTest {

    @Test
    fun `a track id is opaque and stable`() {
        val id = koraTrackId("03 - Some Song.opus")

        assertThat(id).isEqualTo(koraTrackId("03 - Some Song.opus"))
        assertThat(id).matches("t[0-9a-f]{16}")
        assertThat(id).doesNotContain("Song")
        assertThat(id).doesNotContain(".")
    }

    @Test
    fun `different tracks get different ids`() {
        assertThat(koraTrackId("a.opus")).isNotEqualTo(koraTrackId("b.opus"))
    }

    @Test
    fun `a track summary carries the tags and never the file`() {
        val summary = trackSummary(song("a.opus", title = "Alpha", artist = "Band", album = "Record", durationMs = 61_000))

        assertThat(summary.id).isEqualTo(koraTrackId("a.opus"))
        assertThat(summary.title).isEqualTo("Alpha")
        assertThat(summary.artist).isEqualTo("Band")
        assertThat(summary.album).isEqualTo("Record")
        assertThat(summary.durationMs).isEqualTo(61_000)
        assertThat(summary.artworkId).isNull()
        assertThat(summary.toString()).doesNotContain("file:")
    }

    @Test
    fun `blank and unknown tags are absent and an unknown duration is zero`() {
        val summary = trackSummary(song("a.opus", artist = " ", album = null, durationMs = null))

        assertThat(summary.artist).isNull()
        assertThat(summary.album).isNull()
        assertThat(summary.durationMs).isEqualTo(0)
    }

    @Test
    fun `the status follows what the player does`() {
        assertThat(watchStatus(hasMedia = false, ended = false, playing = false)).isEqualTo(WatchStatus.IDLE)
        assertThat(watchStatus(hasMedia = false, ended = true, playing = true)).isEqualTo(WatchStatus.IDLE)
        assertThat(watchStatus(hasMedia = true, ended = true, playing = false)).isEqualTo(WatchStatus.ENDED)
        assertThat(watchStatus(hasMedia = true, ended = false, playing = true)).isEqualTo(WatchStatus.PLAYING)
        assertThat(watchStatus(hasMedia = true, ended = false, playing = false)).isEqualTo(WatchStatus.PAUSED)
    }

    @Test
    fun `sound is expected while playing, or while the next song loads and nothing holds it back`() {
        assertThat(soundExpected(isPlaying = true, playWhenReady = true, buffering = false, suppressed = false)).isTrue()
        // Loading the next song while the user wants sound: about to sound, so playing (as the screens show it).
        assertThat(soundExpected(isPlaying = false, playWhenReady = true, buffering = true, suppressed = false)).isTrue()
        // Waiting for headphones: nothing is about to sound.
        assertThat(soundExpected(isPlaying = false, playWhenReady = true, buffering = true, suppressed = true)).isFalse()
        // Paused while loading, or ready but not playing: no sound.
        assertThat(soundExpected(isPlaying = false, playWhenReady = false, buffering = true, suppressed = false)).isFalse()
        assertThat(soundExpected(isPlaying = false, playWhenReady = true, buffering = false, suppressed = false)).isFalse()
    }

    @Test
    fun `a snapshot maps to the contract state at the time of capture`() {
        val state = playbackState(playerState(song("a.opus"), WatchStatus.PAUSED, positionMs = 4_000, shuffle = true), nowMs = 99)

        assertThat(state.status).isEqualTo(PlaybackStatus.PAUSED)
        assertThat(state.positionMs).isEqualTo(4_000)
        assertThat(state.updatedAtEpochMs).isEqualTo(99)
        assertThat(state.track?.id).isEqualTo(koraTrackId("a.opus"))
        assertThat(state.shuffle).isTrue()
    }

    @Test
    fun `each watch status has its contract status`() {
        val expected = mapOf(
            WatchStatus.IDLE to PlaybackStatus.IDLE,
            WatchStatus.PLAYING to PlaybackStatus.PLAYING,
            WatchStatus.PAUSED to PlaybackStatus.PAUSED,
            WatchStatus.ENDED to PlaybackStatus.ENDED,
        )
        expected.forEach { (watch, contract) ->
            assertThat(playbackState(playerState(null, watch), nowMs = 0).status).isEqualTo(contract)
        }
    }

    @Test
    fun `pages are cut at the requested window and capped at 200`() {
        val all = (1..500).toList()

        val page = pageOf(all, PageRequest(limit = 10, offset = 495))
        assertThat(page.items).containsExactly(496, 497, 498, 499, 500).inOrder()
        assertThat(page.total).isEqualTo(500)
        assertThat(page.offset).isEqualTo(495)

        assertThat(pageOf(all, PageRequest(limit = 1_000)).items).hasSize(200)
    }

    @Test
    fun `search matches title, artist or album, ignoring case, and sorts by title`() {
        val songs = listOf(
            song("1", title = "Zulu", artist = "Alpha Band"),
            song("2", title = "alpha song", artist = "Other", album = "None"),
            song("3", title = "Mid", artist = "x", album = "ALPHA record"),
            song("4", title = "Nope", artist = "x", album = "y"),
        )

        assertThat(searchTracks(songs, " Alpha ").map { it.id }).containsExactly("2", "3", "1").inOrder()
    }

    @Test
    fun `a blank search matches nothing`() {
        assertThat(searchTracks(listOf(song("1")), "  ")).isEmpty()
    }

    @Test
    fun `playing in order keeps the list and the start`() {
        val songs = listOf(song("1"), song("2"), song("3"))

        val queue = playQueue(songs, startIndex = 2, shuffle = false, random = Random(1))

        assertThat(queue.tracks).isEqualTo(songs)
        assertThat(queue.startIndex).isEqualTo(2)
    }

    @Test
    fun `shuffling reorders the list itself and starts at its first song`() {
        val songs = (1..20).map { song("$it") }

        val queue = playQueue(songs, startIndex = 5, shuffle = true, random = Random(7))

        assertThat(queue.tracks).containsExactlyElementsIn(songs)
        assertThat(queue.tracks).isNotEqualTo(songs)
        assertThat(queue.startIndex).isEqualTo(0)
    }

    @Test
    fun `the start is checked even when shuffling`() {
        val songs = listOf(song("1"), song("2"))

        for (shuffle in listOf(false, true)) {
            for (start in listOf(-1, 2)) {
                val failure = assertThrows(KoraException::class.java) { playQueue(songs, start, shuffle, Random(1)) }
                assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
            }
        }
    }

    @Test
    fun `ids resolve to the songs in the order asked, repeats included`() {
        val songs = listOf(song("a"), song("b"), song("c"))

        val found = tracksById(listOf(koraTrackId("c"), koraTrackId("a"), koraTrackId("c")), songs)

        assertThat(found.map { it.id }).containsExactly("c", "a", "c").inOrder()
    }

    @Test
    fun `an unknown track id is an invalid payload that does not echo the id`() {
        val failure = assertThrows(KoraException::class.java) { tracksById(listOf("secret-id"), listOf(song("a"))) }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        assertThat(failure.error.message).doesNotContain("secret-id")
    }
}
