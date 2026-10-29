package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.feature.nowplaying.BottomSlot.CAST
import io.sonora.feature.nowplaying.BottomSlot.EQUALIZER
import io.sonora.feature.nowplaying.BottomSlot.FILE_INFO
import io.sonora.feature.nowplaying.BottomSlot.LYRICS
import io.sonora.feature.nowplaying.BottomSlot.OUTPUT
import io.sonora.feature.nowplaying.BottomSlot.UP_NEXT
import io.sonora.feature.nowplaying.HeaderSlot.DJ_CHIP
import io.sonora.feature.nowplaying.HeaderSlot.FAVOURITE
import io.sonora.feature.nowplaying.HeaderSlot.SLEEP_TIMER
import io.sonora.feature.nowplaying.HeaderSlot.TEMPO_BADGE
import org.junit.Test

class DjLayoutTest {

    @Test
    fun `the DJ bottom capsule keeps every button but lyrics`() {
        assertThat(djBottomSlots(output = true, fileInfo = true, cast = true))
            .containsExactly(EQUALIZER, UP_NEXT, OUTPUT, CAST, FILE_INFO).inOrder()
    }

    @Test
    fun `the DJ bottom capsule follows the device and settings like the normal one`() {
        assertThat(djBottomSlots(output = false, fileInfo = false, cast = false)).containsExactly(EQUALIZER, UP_NEXT).inOrder()
        assertThat(djBottomSlots(output = true, fileInfo = false, cast = false)).containsExactly(EQUALIZER, UP_NEXT, OUTPUT).inOrder()
    }

    @Test
    fun `the DJ bottom capsule never shows lyrics`() {
        assertThat(djBottomSlots(output = true, fileInfo = true, cast = true)).doesNotContain(LYRICS)
    }

    @Test
    fun `the bottom row of a mode picks the DJ slots only in DJ mode`() {
        assertThat(bottomSlotsFor(djMode = true, lyrics = true, output = true, fileInfo = true, cast = false))
            .containsExactly(EQUALIZER, UP_NEXT, OUTPUT, FILE_INFO).inOrder()
        assertThat(bottomSlotsFor(djMode = false, lyrics = true, output = true, fileInfo = true, cast = false))
            .containsExactly(EQUALIZER, LYRICS, UP_NEXT, OUTPUT, FILE_INFO).inOrder()
    }

    @Test
    fun `out of DJ mode the header shows the tempo badge, the sleep timer and the star`() {
        assertThat(headerSlots(djMode = false, sleepTimer = true, track = true))
            .containsExactly(TEMPO_BADGE, SLEEP_TIMER, FAVOURITE).inOrder()
    }

    @Test
    fun `in DJ mode the analysis chip replaces the tempo badge`() {
        assertThat(headerSlots(djMode = true, sleepTimer = true, track = true))
            .containsExactly(DJ_CHIP, SLEEP_TIMER, FAVOURITE).inOrder()
    }

    @Test
    fun `the sleep timer and the star show only when there is one and a song`() {
        assertThat(headerSlots(djMode = true, sleepTimer = false, track = false)).containsExactly(DJ_CHIP)
        assertThat(headerSlots(djMode = false, sleepTimer = false, track = true)).containsExactly(TEMPO_BADGE, FAVOURITE).inOrder()
    }

    @Test
    fun `gravity mode applies only out of DJ mode`() {
        assertThat(gravityApplies(gravity = true, djMode = false)).isTrue()
        assertThat(gravityApplies(gravity = true, djMode = true)).isFalse()
        assertThat(gravityApplies(gravity = false, djMode = false)).isFalse()
    }

    @Test
    fun `gravity mode never hangs the groups level on a large screen`() {
        assertThat(gravityApplies(gravity = true, djMode = false, largeScreen = false)).isTrue()
        assertThat(gravityApplies(gravity = true, djMode = false, largeScreen = true)).isFalse()
        assertThat(gravityApplies(gravity = true, djMode = true, largeScreen = true)).isFalse()
        assertThat(gravityApplies(gravity = false, djMode = false, largeScreen = true)).isFalse()
    }

    @Test
    fun `the DJ layout replaces the standard one in both orientations`() {
        assertThat(showsDjLayout(djMode = true)).isTrue()
        assertThat(showsDjLayout(djMode = false)).isFalse()
    }

    @Test
    fun `lyrics stay closed in DJ mode`() {
        assertThat(lyricsShown(showLyrics = true, djMode = true)).isFalse()
        assertThat(lyricsShown(showLyrics = true, djMode = false)).isTrue()
        assertThat(lyricsShown(showLyrics = false, djMode = false)).isFalse()
    }

    @Test
    fun `the shared artwork lands only on the big artwork`() {
        assertThat(sharesArtwork(showLyrics = false, gravity = false, djMode = false)).isTrue()
        assertThat(sharesArtwork(showLyrics = true, gravity = false, djMode = false)).isFalse()
        assertThat(sharesArtwork(showLyrics = false, gravity = true, djMode = false)).isFalse()
        assertThat(sharesArtwork(showLyrics = false, gravity = false, djMode = true)).isFalse()
    }

    @Test
    fun `the backdrop drifts only out of DJ mode`() {
        assertThat(backdropDrifts(djMode = false, sheetSettled = true, playing = true)).isTrue()
        assertThat(backdropDrifts(djMode = true, sheetSettled = true, playing = true)).isFalse()
    }

    @Test
    fun `the backdrop holds still while nothing plays`() {
        // The drift is the ambient motion of a playing song: paused, an untouched Now Playing draws nothing at all.
        assertThat(backdropDrifts(djMode = false, sheetSettled = true, playing = false)).isFalse()
    }

    @Test
    fun `the backdrop holds still while the sheet moves`() {
        // Every drift step redraws the backdrop and so re-samples every translucent control over it: never during an opening, a
        // closing or a drag, where the frames are already the heaviest.
        assertThat(backdropDrifts(djMode = false, sheetSettled = false, playing = true)).isFalse()
        assertThat(backdropDrifts(djMode = true, sheetSettled = false, playing = true)).isFalse()
    }

    @Test
    fun `the standard header scrolls whenever its arrangement is not hidden, playing or not`() {
        assertThat(headerScrolls(hidden = false, djMode = false, playing = false)).isTrue()
        assertThat(headerScrolls(hidden = false, djMode = false, playing = true)).isTrue()
        assertThat(headerScrolls(hidden = true, djMode = false, playing = true)).isFalse()
    }

    @Test
    fun `the DJ header scrolls only while playing in a visible arrangement`() {
        assertThat(headerScrolls(hidden = false, djMode = true, playing = true)).isTrue()
        assertThat(headerScrolls(hidden = false, djMode = true, playing = false)).isFalse()
        assertThat(headerScrolls(hidden = true, djMode = true, playing = true)).isFalse()
    }

    @Test
    fun `the DJ position asks for frames only while playing in the shown arrangement`() {
        assertThat(djPositionFrames(playing = true, shown = true)).isTrue()
        assertThat(djPositionFrames(playing = false, shown = true)).isFalse()
        assertThat(djPositionFrames(playing = true, shown = false)).isFalse()
    }

    @Test
    fun `a paused DJ screen runs no per-frame driver`() {
        val playing = false
        assertThat(backdropDrifts(djMode = true, sheetSettled = true, playing = playing)).isFalse()
        assertThat(headerScrolls(hidden = false, djMode = true, playing = playing)).isFalse()
        assertThat(djPositionFrames(playing = playing, shown = true)).isFalse()
    }

    @Test
    fun `the deck layout is DJ's or DUB's by their flags, and the standard one with neither`() {
        assertThat(deckLayout(djMode = false, dubMode = false)).isEqualTo(DeckLayout.STANDARD)
        assertThat(deckLayout(djMode = true, dubMode = false)).isEqualTo(DeckLayout.DJ)
        assertThat(deckLayout(djMode = false, dubMode = true)).isEqualTo(DeckLayout.DUB)
        // The flags are exclusive; for the instant both read on, the layout being left keeps its place.
        assertThat(deckLayout(djMode = true, dubMode = true)).isEqualTo(DeckLayout.DJ)
        assertThat(DeckLayout.DUB.deckOn).isTrue()
        assertThat(DeckLayout.DJ.deckOn).isTrue()
        assertThat(DeckLayout.STANDARD.deckOn).isFalse()
    }
}
