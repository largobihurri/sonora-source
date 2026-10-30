package io.sonora.core.kora

import com.google.common.truth.Truth.assertThat
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackStatus
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.playlist.PlaylistResolution
import org.junit.Assert.assertThrows
import org.junit.Test

class KoraMappingTest {

    @Test
    fun `a track keeps its tags and gets an opaque artwork id`() {
        val summary = trackSummary(track(7, title = "Song", artist = "Band", album = "Record", durationMs = 61_000))

        assertThat(summary.id).isEqualTo("7")
        assertThat(summary.title).isEqualTo("Song")
        assertThat(summary.artist).isEqualTo("Band")
        assertThat(summary.album).isEqualTo("Record")
        assertThat(summary.durationMs).isEqualTo(61_000)
        assertThat(summary.artworkId).isEqualTo("a:1")
    }

    @Test
    fun `a track without artwork has no artwork id`() {
        assertThat(trackSummary(track(7, artworkUri = null)).artworkId).isNull()
    }

    @Test
    fun `the tracks of one album share the artwork id and another album has its own`() {
        val first = trackSummary(track(1, albumId = 40))
        val second = trackSummary(track(2, albumId = 40))
        val other = trackSummary(track(3, albumId = 41))

        assertThat(first.artworkId).isEqualTo("a:40")
        assertThat(second.artworkId).isEqualTo(first.artworkId)
        assertThat(other.artworkId).isEqualTo("a:41")
        assertThat(first.artworkId).doesNotContain("/")
    }

    @Test
    fun `a playlist takes the album key of its first playable track with artwork`() {
        val tracks = listOf(track(1, albumId = 7, artworkUri = null), track(2, albumId = 9), track(3, albumId = 8))
        val stored = playlist(5, "Mix", *tracks.toTypedArray())

        val summary = playlistSummary(PlaylistResolution.resolve(stored, tracks.associateBy { it.uri }))

        assertThat(summary.artworkId).isEqualTo("a:9")
    }

    @Test
    fun `blank tags read as absent`() {
        val summary = trackSummary(track(7, artist = " ", album = ""))

        assertThat(summary.artist).isNull()
        assertThat(summary.album).isNull()
    }

    @Test
    fun `a summary never carries a uri or a path`() {
        val summary = trackSummary(track(7))

        assertThat(summary.toString()).doesNotContain("content://")
        assertThat(summary.toString()).doesNotContain("/")
    }

    @Test
    fun `a playlist counts only the playable tracks and takes the first artwork`() {
        val library = mapOf(track(1, artworkUri = null).let { it.uri to it }, track(2).let { it.uri to it })
        val stored = playlist(5, "Mix", track(1, artworkUri = null), track(2), track(3))

        val summary = playlistSummary(PlaylistResolution.resolve(stored, library))

        assertThat(summary.id).isEqualTo("5")
        assertThat(summary.name).isEqualTo("Mix")
        assertThat(summary.trackCount).isEqualTo(2)
        assertThat(summary.artworkId).isEqualTo("a:1")
    }

    @Test
    fun `a playlist without artwork has no artwork id`() {
        val library = mapOf(track(1, artworkUri = null).let { it.uri to it })

        val summary = playlistSummary(PlaylistResolution.resolve(playlist(5, "Mix", track(1)), library))

        assertThat(summary.artworkId).isNull()
    }

    @Test
    fun `playing state maps to playing with the capture time`() {
        val state = playbackState(playing(track(3), isPlaying = true, positionMs = 42_000, shuffle = true), nowMs = 1_000)

        assertThat(state.status).isEqualTo(PlaybackStatus.PLAYING)
        assertThat(state.positionMs).isEqualTo(42_000)
        assertThat(state.updatedAtEpochMs).isEqualTo(1_000)
        assertThat(state.shuffle).isTrue()
        assertThat(state.track?.id).isEqualTo("3")
    }

    @Test
    fun `a stopped player with a track is paused`() {
        assertThat(playbackState(playing(track(3), isPlaying = false), nowMs = 0).status).isEqualTo(PlaybackStatus.PAUSED)
    }

    @Test
    fun `no current track is idle`() {
        assertThat(playbackState(playing(null, isPlaying = false), nowMs = 0).status).isEqualTo(PlaybackStatus.IDLE)
    }

    @Test
    fun `a disconnected player is idle whatever it still holds`() {
        val state = playing(track(3), isPlaying = true, connected = false)

        assertThat(playbackState(state, nowMs = 0).status).isEqualTo(PlaybackStatus.IDLE)
    }

    @Test
    fun `the default player state is idle without a track`() {
        val state = playbackState(PlaybackState(), nowMs = 5)

        assertThat(state.status).isEqualTo(PlaybackStatus.IDLE)
        assertThat(state.track).isNull()
        assertThat(state.shuffle).isFalse()
    }

    @Test
    fun `numeric ids parse`() {
        assertThat(parseId("42", "playlist")).isEqualTo(42L)
    }

    @Test
    fun `a non numeric id is an invalid payload that does not echo the input`() {
        val failure = assertThrows(KoraException::class.java) { parseId("content://x/1", "playlist") }

        assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        assertThat(failure.error.message).doesNotContain("content://")
    }

    @Test
    fun `every 64-bit id Sonora hands out parses back`() {
        val big = track(7_636_189_893_023_974_685, albumId = 8_536_412_284_820_588_170)
        val converted = track(-1, albumId = -1)

        listOf(big, converted, track(Long.MAX_VALUE, albumId = Long.MIN_VALUE)).forEach { song ->
            val summary = trackSummary(song)
            assertThat(parseId(summary.id, "track")).isEqualTo(song.id)
            assertThat(albumIdOfArtwork(summary.artworkId!!)).isEqualTo(song.albumId)
        }
        assertThat(parseId("7636189893023974685", "track")).isEqualTo(7_636_189_893_023_974_685)
        assertThat(albumIdOfArtwork("a:8536412284820588170")).isEqualTo(8_536_412_284_820_588_170)
        assertThat(parseId("-1", "track")).isEqualTo(-1L)
    }

    @Test
    fun `an empty, malformed or oversized id is an invalid payload`() {
        val malformed = listOf(
            "", " ", "-", "--1", "+1", "1.5", "1a", "abc", " 1", "1 ", "\t1", "1\n", "- 1",
            "9223372036854775808", "-9223372036854775809", "99999999999999999999", "0000000000000000000001",
        )
        malformed.forEach { bad ->
            val failure = assertThrows(KoraException::class.java) { parseId(bad, "track") }
            assertThat(failure.error.code).isEqualTo(ErrorCode.INVALID_PAYLOAD)
        }
    }

    @Test
    fun `a page drops the offset, takes the limit and reports the whole size`() {
        val page = pageOf((1..10).toList(), PageRequest(limit = 3, offset = 4))

        assertThat(page.items).containsExactly(5, 6, 7).inOrder()
        assertThat(page.total).isEqualTo(10)
        assertThat(page.offset).isEqualTo(4)
    }

    @Test
    fun `a page past the end is empty but keeps the total`() {
        val page = pageOf((1..3).toList(), PageRequest(limit = 5, offset = 9))

        assertThat(page.items).isEmpty()
        assertThat(page.total).isEqualTo(3)
    }

    @Test
    fun `a page never exceeds the maximum size`() {
        val page = pageOf((1..1_000).toList(), PageRequest(limit = 5_000, offset = 0))

        assertThat(page.items).hasSize(MAX_PAGE_SIZE)
        assertThat(page.total).isEqualTo(1_000)
    }
}
