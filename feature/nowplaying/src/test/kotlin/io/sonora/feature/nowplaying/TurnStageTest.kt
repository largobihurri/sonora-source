package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import io.sonora.feature.nowplaying.NowPlayingLayout.Landscape
import io.sonora.feature.nowplaying.NowPlayingLayout.Portrait
import org.junit.Test

/** The stage's measure wiring (T61, R3-stage-untested) and its wait for a turning window (R3-wait-latch). */
class TurnStageTest {

    private val portraitRoot = IntSize(1080, 2400)
    private val landscapeRoot = IntSize(2400, 1080)

    // A phone with its camera cutout at the top: upright, status bar and cutout above, the navigation bar below.
    private val portraitInsets = SafeInsets(left = 0, top = 136, right = 0, bottom = 63)
    private val landscapeInsets = SafeInsets(left = 136, top = 63, right = 0, bottom = 63)

    private var rotation = 0
    private var now = 0L
    private val stage = TurnStage(displayRotation = { rotation }, nowMs = { now })

    @Test
    fun `the first measure shows the window's own arrangement, padded by the live insets`() {
        stage.measured(landscapeRoot, landscapeInsets)

        assertThat(stage.roles.shown).isEqualTo(Landscape)
        assertThat(stage.paddingFor(Landscape, landscapeInsets)).isEqualTo(arrangementPadding(Landscape, landscapeInsets))
        // The other one, before its first visit, pads by these turned back a quarter.
        assertThat(stage.paddingFor(Portrait, landscapeInsets)).isEqualTo(landscapeInsets.quarterTurned())
    }

    @Test
    fun `a quarter turn waits for the window to resize, then shows the new arrangement with its own insets`() {
        stage.measured(portraitRoot, portraitInsets)

        rotation = 1 // the display turned; the window is still portrait for a frame
        stage.measured(portraitRoot, SafeInsets(left = 136, top = 0, right = 0, bottom = 63))
        // Meanwhile the shown arrangement keeps the insets it had (the new ones belong to the turned window).
        assertThat(stage.roles.shown).isEqualTo(Portrait)
        assertThat(stage.paddingFor(Portrait, SafeInsets(left = 136, top = 0, right = 0, bottom = 63))).isEqualTo(portraitInsets)

        stage.measured(landscapeRoot, landscapeInsets)
        assertThat(stage.roles.shown).isEqualTo(Landscape)
        assertThat(stage.paddingFor(Landscape, landscapeInsets)).isEqualTo(arrangementPadding(Landscape, landscapeInsets))
        // Portrait remembers its own for the turn back.
        assertThat(stage.paddingFor(Portrait, landscapeInsets)).isEqualTo(portraitInsets)
    }

    @Test
    fun `a wait for a window that never resizes ends, so the insets keep updating`() {
        stage.measured(portraitRoot, portraitInsets)
        rotation = 1 // e.g. a window that keeps its size in multi-window: the resize never comes
        stage.measured(portraitRoot, portraitInsets)

        now += WINDOW_TURN_WAIT_MILLIS
        val keyboard = portraitInsets.copy(bottom = 900)
        stage.measured(portraitRoot, keyboard)

        assertThat(stage.roles.shown).isEqualTo(Portrait)
        assertThat(stage.paddingFor(Portrait, keyboard)).isEqualTo(keyboard)
        // And it stays cleared: the next change of insets is followed at once.
        stage.measured(portraitRoot, portraitInsets)
        assertThat(stage.paddingFor(Portrait, portraitInsets)).isEqualTo(portraitInsets)
    }

    @Test
    fun `a turn back before the window resized ends the wait at once`() {
        stage.measured(portraitRoot, portraitInsets)
        rotation = 1
        stage.measured(portraitRoot, portraitInsets)
        rotation = 0

        val changed = portraitInsets.copy(top = 150)
        stage.measured(portraitRoot, changed)

        assertThat(stage.paddingFor(Portrait, changed)).isEqualTo(changed)
    }

    // The controls' top row and the window's top bar (T71)

    @Test
    fun `the controls' top row clears the window's top bar by what the padding leaves`() {
        assertThat(topRowClearance(SafeInsets(left = 136, top = 74, right = 0, bottom = 63, bar = 74))).isEqualTo(0)
        // A system that leaves the status bar out of the safe insets in landscape: the top row moves down by all of it.
        assertThat(topRowClearance(SafeInsets(left = 136, top = 0, right = 0, bottom = 63, bar = 90))).isEqualTo(90)
        assertThat(topRowClearance(SafeInsets(left = 0, top = 136, right = 0, bottom = 63, bar = 74))).isEqualTo(0)
    }

    @Test
    fun `the shown arrangement clears the live top bar, even while it waits on remembered insets`() {
        stage.measured(landscapeRoot, landscapeInsets.copy(top = 0, bar = 90))
        assertThat(stage.topRowClearance(Landscape)).isEqualTo(90)

        stage.measured(landscapeRoot, landscapeInsets.copy(bar = 63))
        assertThat(stage.topRowClearance(Landscape)).isEqualTo(0)

        // The turn to portrait has not resized the window yet: the remembered insets, but the bar as it is now.
        rotation = 3
        stage.measured(landscapeRoot, landscapeInsets.copy(top = 0, bar = 90))
        assertThat(stage.roles.shown).isEqualTo(Landscape)
        assertThat(stage.topRowClearance(Landscape)).isEqualTo(27)
    }

    @Test
    fun `an arrangement laid out ahead for the next turn keeps the top bar it had when last shown`() {
        stage.measured(landscapeRoot, landscapeInsets.copy(top = 0, bar = 90))
        rotation = 1
        stage.measured(portraitRoot, portraitInsets.copy(bar = 136))

        assertThat(stage.roles.shown).isEqualTo(Portrait)
        // Measured for the turn back: its remembered padding and bar, so the turn frame relays nothing out.
        assertThat(stage.paddingFor(Landscape, portraitInsets.copy(bar = 136))).isEqualTo(arrangementPadding(Landscape, landscapeInsets.copy(top = 0, bar = 90)))
        assertThat(stage.topRowClearance(Landscape)).isEqualTo(90)
    }

    // --- a block reported by two nodes at once (the DJ swap composes the old and the new arrangement together, D2.10) ---

    private val place = androidx.compose.ui.geometry.Rect(10f, 20f, 110f, 220f)
    private val laterPlace = androidx.compose.ui.geometry.Rect(0f, 0f, 50f, 60f)

    @Test
    fun `a block's place is forgotten when its owner leaves`() {
        val node = Any()
        stage.placed(Portrait, NowPlayingBlock.Artwork, place, node)

        assertThat(stage.shownPlace(NowPlayingBlock.Artwork)).isEqualTo(place)
        stage.forget(Portrait, NowPlayingBlock.Artwork, node)
        assertThat(stage.shownPlace(NowPlayingBlock.Artwork)).isNull()
    }

    @Test
    fun `the arrangement fading out leaving does not forget the place the incoming one reported`() {
        val outgoing = Any()
        val incoming = Any()
        stage.placed(Portrait, NowPlayingBlock.Artwork, place, outgoing)
        stage.placed(Portrait, NowPlayingBlock.Artwork, laterPlace, incoming)

        stage.forget(Portrait, NowPlayingBlock.Artwork, outgoing)

        assertThat(stage.shownPlace(NowPlayingBlock.Artwork)).isEqualTo(laterPlace)
        stage.forget(Portrait, NowPlayingBlock.Artwork, incoming)
        assertThat(stage.shownPlace(NowPlayingBlock.Artwork)).isNull()
    }
}
