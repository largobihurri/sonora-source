package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrossfadePolicyTest {

    private fun track(albumId: Long = 7, disc: Int? = 1, number: Int? = 1, durationMs: Long = 4 * MINUTE) =
        CrossfadeTrack(albumId = albumId, discNumber = disc, trackNumber = number, durationMs = durationMs)

    private fun fade(
        outgoing: CrossfadeTrack,
        incoming: CrossfadeTrack?,
        seconds: Int = 6,
        repeatOne: Boolean = false,
    ): Long = CrossfadePolicy.fadeMs(seconds, outgoing, incoming, repeatOne)

    @Test
    fun `songs from different albums crossfade over the chosen time`() {
        assertThat(fade(track(albumId = 1), track(albumId = 2))).isEqualTo(6_000L)
    }

    @Test
    fun `the next track of the same album in album order stays gapless`() {
        assertThat(fade(track(number = 3), track(number = 4))).isEqualTo(0L)
    }

    @Test
    fun `the first track of the next disc follows the last one of the disc before`() {
        assertThat(fade(track(disc = 1, number = 12), track(disc = 2, number = 1))).isEqualTo(0L)
    }

    @Test
    fun `the same album out of album order crossfades`() {
        assertThat(fade(track(number = 4), track(number = 2))).isEqualTo(6_000L)
        assertThat(fade(track(number = 4), track(number = 6))).isEqualTo(6_000L)
        assertThat(fade(track(disc = 1, number = 3), track(disc = 2, number = 4))).isEqualTo(6_000L)
    }

    @Test
    fun `a missing disc number reads as disc one`() {
        assertThat(fade(track(disc = null, number = 1), track(disc = 1, number = 2))).isEqualTo(0L)
    }

    @Test
    fun `the same album without track numbers is taken as album order`() {
        assertThat(fade(track(number = null), track(number = 5))).isEqualTo(0L)
        assertThat(fade(track(number = 5), track(number = null))).isEqualTo(0L)
    }

    @Test
    fun `tracks without an album never count as the same album`() {
        assertThat(fade(track(albumId = 0, number = 1), track(albumId = 0, number = 2))).isEqualTo(6_000L)
    }

    @Test
    fun `nothing is crossfaded when the setting is off, on repeat one or at the end of the queue`() {
        assertThat(fade(track(albumId = 1), track(albumId = 2), seconds = 0)).isEqualTo(0L)
        assertThat(fade(track(albumId = 1), track(albumId = 2), repeatOne = true)).isEqualTo(0L)
        assertThat(fade(track(albumId = 1), null)).isEqualTo(0L)
    }

    @Test
    fun `the setting is kept within zero to twelve seconds`() {
        assertThat(fade(track(albumId = 1), track(albumId = 2), seconds = 30)).isEqualTo(12_000L)
        assertThat(fade(track(albumId = 1), track(albumId = 2), seconds = -3)).isEqualTo(0L)
        assertThat(Crossfade.clampSeconds(13)).isEqualTo(Crossfade.MAX_SECONDS)
        assertThat(Crossfade.clampSeconds(-1)).isEqualTo(Crossfade.MIN_SECONDS)
        assertThat(Crossfade.DEFAULT_SECONDS).isEqualTo(0)
    }

    @Test
    fun `a fade never takes more than half of either song, so the outgoing one still counts as played`() {
        assertThat(fade(track(albumId = 1, durationMs = 10_000), track(albumId = 2), seconds = 12)).isEqualTo(5_000L)
        assertThat(fade(track(albumId = 1), track(albumId = 2, durationMs = 8_000), seconds = 12)).isEqualTo(4_000L)
    }

    @Test
    fun `songs too short for a one second fade, or of unknown length, are not crossfaded`() {
        assertThat(fade(track(albumId = 1, durationMs = 1_500), track(albumId = 2))).isEqualTo(0L)
        assertThat(fade(track(albumId = 1, durationMs = 0), track(albumId = 2))).isEqualTo(0L)
    }

    @Test
    fun `an incoming song of unknown length does not shorten the fade`() {
        assertThat(fade(track(albumId = 1), track(albumId = 2, durationMs = 0))).isEqualTo(6_000L)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
