package io.sonora.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import io.sonora.feature.nowplaying.ArrangementRole.Hidden
import io.sonora.feature.nowplaying.ArrangementRole.Outgoing
import io.sonora.feature.nowplaying.ArrangementRole.Shown
import io.sonora.feature.nowplaying.NowPlayingBlock.Artwork
import io.sonora.feature.nowplaying.NowPlayingBlock.Controls
import io.sonora.feature.nowplaying.NowPlayingBlock.Handle
import io.sonora.feature.nowplaying.NowPlayingLayout.Landscape
import io.sonora.feature.nowplaying.NowPlayingLayout.Portrait
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class TurnSceneTest {

    private val portraitRoot = IntSize(1080, 2400)
    private val landscapeRoot = IntSize(2400, 1080)
    private val portraitScreen = Size(1080f, 2400f)
    private val landscapeScreen = Size(2400f, 1080f)

    // A phone-like Now Playing: artwork above the controls in portrait, side by side in landscape.
    private val portrait = mapOf(
        Handle to Rect(520f, 130f, 560f, 145f),
        Artwork to Rect(74f, 230f, 1006f, 1162f),
        Controls to Rect(74f, 1162f, 1006f, 2290f),
    )
    private val landscape = mapOf(
        Handle to Rect(1180f, 20f, 1220f, 35f),
        Artwork to Rect(200f, 80f, 1150f, 1030f),
        Controls to Rect(1255f, 80f, 2200f, 1030f),
    )

    /** Portrait to landscape by [quarterTurns] (1 or 3), the landscape arrangement laid out beforehand. */
    private fun toLandscape(quarterTurns: Int, ends: Map<NowPlayingBlock, Rect> = landscape) = BlockTurn(
        incoming = Landscape,
        outgoing = Portrait,
        old = portrait,
        oldScreen = portraitScreen,
        quarterTurns = quarterTurns,
        ends = ends,
        screen = landscapeScreen,
    )

    // Window sizes and shapes.

    @Test
    fun `each arrangement keeps its own shape whichever way the window is turned`() {
        for (root in listOf(portraitRoot, landscapeRoot)) {
            assertThat(arrangementSize(Portrait, root)).isEqualTo(IntSize(1080, 2400))
            assertThat(arrangementSize(Landscape, root)).isEqualTo(IntSize(2400, 1080))
        }
    }

    @Test
    fun `the arrangement for the window's shape is laid out at exactly the window's size`() {
        for (root in listOf(portraitRoot, landscapeRoot, IntSize(800, 800), IntSize(1280, 800))) {
            assertThat(arrangementSize(nowPlayingLayout(root), root)).isEqualTo(root)
        }
    }

    @Test
    fun `a window wider than tall in pixels shows the landscape arrangement, any other the portrait one`() {
        assertThat(nowPlayingLayout(landscapeRoot)).isEqualTo(Landscape)
        assertThat(nowPlayingLayout(portraitRoot)).isEqualTo(Portrait)
        assertThat(nowPlayingLayout(IntSize(900, 900))).isEqualTo(Portrait)
    }

    // Safe insets per arrangement.

    @Test
    fun `an arrangement pads by the insets last seen while it was shown`() {
        val memory = InsetMemory()
        val portraitInsets = SafeInsets(left = 0, top = 130, right = 0, bottom = 60)
        val landscapeInsets = SafeInsets(left = 130, top = 70, right = 0, bottom = 60)
        memory.shown(Portrait, portraitInsets)
        memory.shown(Landscape, landscapeInsets)
        // The window turns back to portrait: the landscape arrangement keeps what it saw.
        memory.shown(Portrait, portraitInsets)
        assertThat(memory.insetsFor(Portrait)).isEqualTo(portraitInsets)
        assertThat(memory.insetsFor(Landscape)).isEqualTo(landscapeInsets)
    }

    @Test
    fun `before its first visit an arrangement estimates its insets as the other one's turned a quarter`() {
        val memory = InsetMemory()
        memory.shown(Portrait, SafeInsets(left = 0, top = 130, right = 0, bottom = 60))
        // The old top edge on the left, the old bottom edge on the right.
        assertThat(memory.insetsFor(Landscape)).isEqualTo(SafeInsets(left = 130, top = 0, right = 60, bottom = 0))
    }

    @Test
    fun `with no insets seen yet an arrangement pads by none`() {
        assertThat(InsetMemory().insetsFor(Portrait)).isEqualTo(SafeInsets.Zero)
    }

    @Test
    fun `remembering an arrangement's insets reports whether they changed`() {
        val memory = InsetMemory()
        val insets = SafeInsets(left = 0, top = 130, right = 0, bottom = 60)
        assertThat(memory.shown(Portrait, insets)).isTrue()
        assertThat(memory.shown(Portrait, insets)).isFalse()
        assertThat(memory.shown(Portrait, insets.copy(bottom = 0))).isTrue()
    }

    @Test
    fun `the landscape arrangement pads both sides by the larger side inset, the portrait one by the insets as they are`() {
        val insets = SafeInsets(left = 130, top = 70, right = 20, bottom = 60)
        assertThat(arrangementPadding(Landscape, insets)).isEqualTo(SafeInsets(left = 130, top = 70, right = 130, bottom = 60))
        assertThat(arrangementPadding(Portrait, insets)).isEqualTo(insets)
    }

    // Telling a turn of the window from other changes.

    @Test
    fun `a quarter turn of the display waits for the window's new size`() {
        assertThat(windowTurn(fromRotation = 0, toRotation = 1, fromRoot = portraitRoot, toRoot = portraitRoot)).isEqualTo(WindowTurn.Wait)
        assertThat(windowTurn(fromRotation = 0, toRotation = 1, fromRoot = portraitRoot, toRoot = landscapeRoot)).isEqualTo(WindowTurn.Turn(1))
        assertThat(windowTurn(fromRotation = 0, toRotation = 3, fromRoot = portraitRoot, toRoot = landscapeRoot)).isEqualTo(WindowTurn.Turn(3))
    }

    @Test
    fun `a half turn of the display needs no new size`() {
        assertThat(windowTurn(fromRotation = 1, toRotation = 3, fromRoot = landscapeRoot, toRoot = landscapeRoot)).isEqualTo(WindowTurn.Turn(2))
    }

    @Test
    fun `an unturned display is no turn, and a turn into an unrelated size is skipped`() {
        assertThat(windowTurn(fromRotation = 1, toRotation = 1, fromRoot = landscapeRoot, toRoot = portraitRoot)).isEqualTo(WindowTurn.None)
        assertThat(windowTurn(fromRotation = 0, toRotation = 1, fromRoot = portraitRoot, toRoot = IntSize(2400, 540))).isEqualTo(WindowTurn.Skip)
        assertThat(windowTurn(fromRotation = 1, toRotation = 3, fromRoot = landscapeRoot, toRoot = IntSize(2400, 540))).isEqualTo(WindowTurn.Skip)
    }

    // Which arrangement is shown, turning away or hidden.

    @Test
    fun `a turn shows the new arrangement while the old one turns away, and hides the old one when it ends`() {
        val still = TurnRoles(shown = Portrait)
        assertThat(still.role(Portrait)).isEqualTo(Shown)
        assertThat(still.role(Landscape)).isEqualTo(Hidden)

        val turning = still.turnedTo(Landscape)
        assertThat(turning.role(Landscape)).isEqualTo(Shown)
        assertThat(turning.role(Portrait)).isEqualTo(Outgoing)

        val settled = turning.settled()
        assertThat(settled.role(Landscape)).isEqualTo(Shown)
        assertThat(settled.role(Portrait)).isEqualTo(Hidden)
    }

    @Test
    fun `a second turn half way turns away the arrangement that was coming in`() {
        val second = TurnRoles(shown = Portrait).turnedTo(Landscape).turnedTo(Portrait)
        assertThat(second.role(Portrait)).isEqualTo(Shown)
        assertThat(second.role(Landscape)).isEqualTo(Outgoing)
    }

    @Test
    fun `a half turn keeps the same arrangement with nothing turning away`() {
        val half = TurnRoles(shown = Landscape).turnedTo(Landscape)
        assertThat(half.outgoing).isNull()
        assertThat(half.role(Landscape)).isEqualTo(Shown)
        assertThat(half.role(Portrait)).isEqualTo(Hidden)
    }

    @Test
    fun `both arrangements stay composed while turns keep both, otherwise only the shown one and one turning away`() {
        val still = TurnRoles(shown = Landscape)
        assertThat(still.composed(keepsBoth = true)).containsExactly(Portrait, Landscape)
        assertThat(still.composed(keepsBoth = false)).containsExactly(Landscape)
        assertThat(still.turnedTo(Portrait).composed(keepsBoth = false)).containsExactly(Portrait, Landscape)
        assertThat(still.turnedTo(Portrait).settled().composed(keepsBoth = false)).containsExactly(Portrait)
    }

    // Each block's layer through a turn.

    @Test
    fun `on the turn's first frame every block of the old arrangement is exactly where it was on the screen`() {
        for (quarters in listOf(1, 3)) {
            val turn = toLandscape(quarters)
            for ((block, place) in portrait) {
                val layer = turn.layer(Portrait, block, place, time = 0f, opaque = block == Artwork)
                assertThat(layer.alpha).isEqualTo(1f)
                assertThat(layer.scale).isWithin(1e-4f).of(1f)
                for (point in corners(place) + place.center) {
                    assertNear(drawn(layer, place, point), translucent(point, quarters, portraitScreen))
                }
            }
        }
    }

    @Test
    fun `on the turn's first frame the new controls and handle are invisible and the new artwork waits under the old one`() {
        val turn = toLandscape(1)
        assertThat(turn.layer(Landscape, Controls, landscape.getValue(Controls), time = 0f, opaque = false).alpha).isEqualTo(0f)
        assertThat(turn.layer(Landscape, Handle, landscape.getValue(Handle), time = 0f, opaque = false).alpha).isEqualTo(0f)

        val old = turn.layer(Portrait, Artwork, portrait.getValue(Artwork), time = 0f, opaque = true)
        val new = turn.layer(Landscape, Artwork, landscape.getValue(Artwork), time = 0f, opaque = true)
        assertThat(new.alpha).isEqualTo(1f)
        // Same centre and angle, fitted into the old artwork's size: wholly covered by the old picture.
        assertNear(landscape.getValue(Artwork).center + new.translation, portrait.getValue(Artwork).center + old.translation)
        assertThat(new.rotation).isEqualTo(old.rotation)
        assertThat(landscape.getValue(Artwork).width * new.scale).isWithin(0.5f).of(portrait.getValue(Artwork).width)
    }

    @Test
    fun `on the turn's last frame the new arrangement is exactly its layout and the old one is gone`() {
        for (quarters in listOf(1, 3)) {
            val turn = toLandscape(quarters)
            for ((block, place) in landscape) {
                val layer = turn.layer(Landscape, block, place, time = 1f, opaque = block == Artwork)
                assertNear(layer.translation, Offset.Zero)
                assertThat(layer.rotation).isWithin(1e-3f).of(0f)
                assertThat(layer.scale).isWithin(1e-4f).of(1f)
                assertThat(layer.alpha).isEqualTo(1f)
            }
            for ((block, place) in portrait) {
                assertThat(turn.layer(Portrait, block, place, time = 1f, opaque = block == Artwork).alpha).isEqualTo(0f)
            }
        }
    }

    @Test
    fun `half way both pictures of a moving block share one frame, each fitted into it`() {
        val turn = toLandscape(3)
        for (block in listOf(Artwork, Controls)) {
            val old = turn.layer(Portrait, block, portrait.getValue(block), time = 0.5f, opaque = block == Artwork)
            val new = turn.layer(Landscape, block, landscape.getValue(block), time = 0.5f, opaque = block == Artwork)
            assertNear(portrait.getValue(block).center + old.translation, landscape.getValue(block).center + new.translation)
            assertThat(old.rotation).isWithin(1e-3f).of(new.rotation)
            assertThat(old.alpha).isEqualTo(snapshotAlpha(0.5f))
            assertThat(new.alpha).isEqualTo(liveAlpha(0.5f, opaque = block == Artwork))
        }
        // The artwork and the controls turn the same way at the same pace.
        val artwork = turn.layer(Landscape, Artwork, landscape.getValue(Artwork), time = 0.5f, opaque = true)
        val controls = turn.layer(Landscape, Controls, landscape.getValue(Controls), time = 0.5f, opaque = false)
        assertThat(artwork.rotation).isWithin(1e-3f).of(controls.rotation)
    }

    @Test
    fun `the old drag handle stays where it was on the screen while it fades out`() {
        val turn = toLandscape(1)
        val place = portrait.getValue(Handle)
        for (time in listOf(0f, 0.3f, 0.5f)) {
            val layer = turn.layer(Portrait, Handle, place, time, opaque = false)
            assertNear(drawn(layer, place, place.center), translucent(place.center, 1, portraitScreen))
            assertThat(layer.alpha).isEqualTo(snapshotAlpha(time))
        }
        assertThat(turn.layer(Landscape, Handle, landscape.getValue(Handle), time = 0.5f, opaque = false))
            .isEqualTo(BlockLayer(alpha = liveAlpha(0.5f, opaque = false)))
    }

    @Test
    fun `a new block placed only after the turn started is invisible until then and then starts on the old picture`() {
        val turn = toLandscape(1, ends = emptyMap())
        assertThat(turn.layer(Landscape, Artwork, landscape.getValue(Artwork), time = 0f, opaque = true).alpha).isEqualTo(0f)
        // The old picture holds its start until the new place is known.
        val old = turn.layer(Portrait, Artwork, portrait.getValue(Artwork), time = 0f, opaque = true)
        assertNear(drawn(old, portrait.getValue(Artwork), portrait.getValue(Artwork).center), translucent(portrait.getValue(Artwork).center, 1, portraitScreen))

        assertThat(turn.placed(Artwork, landscape.getValue(Artwork), time = 0f)).isTrue()
        assertThat(turn.placed(Artwork, landscape.getValue(Artwork), time = 0f)).isFalse()
        val new = turn.layer(Landscape, Artwork, landscape.getValue(Artwork), time = 0f, opaque = true)
        assertThat(new.alpha).isEqualTo(1f)
        assertNear(landscape.getValue(Artwork).center + new.translation, translucent(portrait.getValue(Artwork).center, 1, portraitScreen))
        assertThat(turn.layer(Landscape, Artwork, landscape.getValue(Artwork), time = 1f, opaque = true)).isEqualTo(BlockLayer.Still)
    }

    @Test
    fun `a new block re-placed mid-turn continues from where it is and ends exactly on its new place`() {
        val turn = toLandscape(1)
        val place = landscape.getValue(Controls)
        val before = turn.layer(Landscape, Controls, place, time = 0.3f, opaque = false)
        val moved = place.translate(0f, 40f)
        assertThat(turn.placed(Controls, moved, time = 0.3f)).isTrue()
        val after = turn.layer(Landscape, Controls, moved, time = 0.3f, opaque = false)
        assertNear(place.center + before.translation, moved.center + after.translation)
        assertThat(after.rotation).isWithin(1e-3f).of(before.rotation)
        assertThat(turn.layer(Landscape, Controls, moved, time = 1f, opaque = false)).isEqualTo(BlockLayer.Still)
    }

    @Test
    fun `an arrangement taking no part in the turn draws nothing`() {
        val half = BlockTurn(Landscape, outgoing = null, old = landscape, oldScreen = landscapeScreen, quarterTurns = 2, ends = landscape, screen = landscapeScreen)
        assertThat(half.layer(Portrait, Artwork, portrait.getValue(Artwork), time = 0.5f, opaque = true)).isEqualTo(BlockLayer.Invisible)
    }

    @Test
    fun `outside a turn the shown arrangement is drawn as laid out and any other not at all`() {
        assertThat(stageBlockLayer(Shown, turn = null, Portrait, Artwork, place = null, time = 1f, opaque = true)).isEqualTo(BlockLayer.Still)
        assertThat(stageBlockLayer(Hidden, turn = null, Landscape, Artwork, landscape.getValue(Artwork), time = 1f, opaque = true))
            .isEqualTo(BlockLayer.Invisible)
        // During a turn, a block not laid out yet waits invisible.
        assertThat(stageBlockLayer(Shown, toLandscape(1), Landscape, Controls, place = null, time = 0f, opaque = false))
            .isEqualTo(BlockLayer.Invisible)
    }

    @Test
    fun `in a half turn the one arrangement turns from the old picture and stays opaque`() {
        val half = BlockTurn(Landscape, outgoing = null, old = landscape, oldScreen = landscapeScreen, quarterTurns = 2, ends = landscape, screen = landscapeScreen)
        for (block in listOf(Artwork, Controls)) {
            val place = landscape.getValue(block)
            val start = half.layer(Landscape, block, place, time = 0f, opaque = block == Artwork)
            assertThat(start.alpha).isEqualTo(1f)
            for (point in corners(place)) assertNear(drawn(start, place, point), translucent(point, 2, landscapeScreen))
            assertThat(half.layer(Landscape, block, place, time = 0.5f, opaque = false).alpha).isEqualTo(1f)
            assertThat(half.layer(Landscape, block, place, time = 1f, opaque = false)).isEqualTo(BlockLayer.Still)
        }
    }

    /** Where a graphics layer drawing a block laid out at [place] puts [point] (both in the window's coordinates). */
    private fun drawn(layer: BlockLayer, place: Rect, point: Offset): Offset {
        val v = (point - place.center) * layer.scale
        val radians = Math.toRadians(layer.rotation.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return place.center + layer.translation + Offset(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    /** Where [point] of a screen of [oldScreen] size is, on the same physical screen, after the display turned [quarterTurns] clockwise. */
    private fun translucent(point: Offset, quarterTurns: Int, oldScreen: Size): Offset = when (quarterTurns) {
        1 -> Offset(point.y, oldScreen.width - point.x)
        2 -> Offset(oldScreen.width - point.x, oldScreen.height - point.y)
        3 -> Offset(oldScreen.height - point.y, point.x)
        else -> point
    }

    private fun corners(rect: Rect) = listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight)

    private fun assertNear(actual: Offset, expected: Offset) {
        assertThat(abs(actual.x - expected.x) + abs(actual.y - expected.y)).isLessThan(0.05f)
    }

    @Test
    fun `the window's top bar stays on top when the insets are estimated for the other orientation`() {
        // The status bar is always at the window's top, whichever way the device turned: the estimate keeps it.
        val portrait = SafeInsets(left = 0, top = 136, right = 0, bottom = 63, bar = 136)
        assertThat(portrait.quarterTurned().bar).isEqualTo(136)
        val memory = InsetMemory()
        memory.shown(Portrait, portrait)
        assertThat(memory.insetsFor(Landscape).bar).isEqualTo(136)
        assertThat(arrangementPadding(Landscape, portrait.quarterTurned()).bar).isEqualTo(136)
    }
}
