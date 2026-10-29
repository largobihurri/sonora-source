package io.sonora.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import org.junit.Test

class RotatingBlocksTest {

    private val portrait = Size(1080f, 2400f)
    private val landscape = Size(2400f, 1080f)

    // A phone-like Now Playing: artwork above the controls in portrait, side by side in landscape.
    private val portraitArtwork = Rect(74f, 230f, 1006f, 1162f)
    private val portraitControls = Rect(74f, 1162f, 1006f, 2290f)
    private val landscapeArtwork = Rect(200f, 80f, 1150f, 1030f)
    private val landscapeControls = Rect(1255f, 80f, 2200f, 1030f)

    private fun turnToLandscape(quarterTurns: Int) = TurnMotion(
        starts = mapOf(
            NowPlayingBlock.Artwork to turnStartFrame(portraitArtwork, portrait, quarterTurns),
            NowPlayingBlock.Controls to turnStartFrame(portraitControls, portrait, quarterTurns),
        ),
        ends = mapOf(NowPlayingBlock.Artwork to landscapeArtwork.frame(), NowPlayingBlock.Controls to landscapeControls.frame()),
        pivot = landscape.center,
    )

    private fun turnToPortrait(quarterTurns: Int) = TurnMotion(
        starts = mapOf(
            NowPlayingBlock.Artwork to turnStartFrame(landscapeArtwork, landscape, quarterTurns),
            NowPlayingBlock.Controls to turnStartFrame(landscapeControls, landscape, quarterTurns),
        ),
        ends = mapOf(NowPlayingBlock.Artwork to portraitArtwork.frame(), NowPlayingBlock.Controls to portraitControls.frame()),
        pivot = portrait.center,
    )

    private val samples = (0..400).map { it / 400f }

    @Test
    fun `quarter turns follow the display rotation clockwise, wrapping around`() {
        assertThat(quarterTurns(from = 0, to = 1)).isEqualTo(1)
        assertThat(quarterTurns(from = 0, to = 3)).isEqualTo(3)
        assertThat(quarterTurns(from = 1, to = 0)).isEqualTo(3)
        assertThat(quarterTurns(from = 3, to = 0)).isEqualTo(1)
        assertThat(quarterTurns(from = 1, to = 3)).isEqualTo(2)
        assertThat(quarterTurns(from = 3, to = 1)).isEqualTo(2)
        assertThat(quarterTurns(from = 2, to = 2)).isEqualTo(0)
    }

    @Test
    fun `a quarter turn maps the old top edge onto the new left edge`() {
        // Display 0 -> 90: the phone turned anticlockwise, so its top edge is now on the user's left.
        val top = Rect(left = 100f, top = 0f, right = 900f, bottom = 200f)
        assertThat(top.turned(1, portrait)).isEqualTo(Rect(left = 0f, top = 180f, right = 200f, bottom = 980f))
    }

    @Test
    fun `three quarter turns map the old top edge onto the new right edge`() {
        val top = Rect(left = 100f, top = 0f, right = 900f, bottom = 200f)
        assertThat(top.turned(3, portrait)).isEqualTo(Rect(left = 2200f, top = 100f, right = 2400f, bottom = 900f))
    }

    @Test
    fun `a half turn mirrors a rect through the screen centre`() {
        val left = Rect(left = 100f, top = 50f, right = 1000f, bottom = 950f)
        assertThat(left.turned(2, landscape)).isEqualTo(Rect(left = 1400f, top = 130f, right = 2300f, bottom = 1030f))
    }

    @Test
    fun `no turn leaves a rect where it was`() {
        val rect = Rect(10f, 20f, 30f, 40f)
        assertThat(rect.turned(0, portrait)).isEqualTo(rect)
    }

    @Test
    fun `a block starts exactly on its old place on the display, at its old size, counter-rotated`() {
        val anticlockwise = turnStartFrame(portraitArtwork, portrait, quarterTurns = 1)
        assertThat(anticlockwise.center).isEqualTo(portraitArtwork.turned(1, portrait).center)
        assertThat(anticlockwise.size).isEqualTo(portraitArtwork.size)
        assertThat(anticlockwise.rotation).isEqualTo(-90f)

        assertThat(turnStartFrame(portraitArtwork, portrait, quarterTurns = 3).rotation).isEqualTo(90f)
        assertThat(turnStartFrame(portraitArtwork, portrait, quarterTurns = 2).rotation).isEqualTo(180f)
        assertThat(turnStartFrame(portraitArtwork, portrait, quarterTurns = 0)).isEqualTo(portraitArtwork.frame())
    }

    @Test
    fun `the turn starts on the old frames and ends exactly on the new layout, upright`() {
        for (turns in listOf(1, 3)) {
            val motion = turnToLandscape(turns)
            assertThat(motion.frame(NowPlayingBlock.Artwork, 0f)).isEqualTo(turnStartFrame(portraitArtwork, portrait, turns))
            assertThat(motion.frame(NowPlayingBlock.Controls, 0f)).isEqualTo(turnStartFrame(portraitControls, portrait, turns))
            assertThat(motion.frame(NowPlayingBlock.Artwork, 1f)).isEqualTo(landscapeArtwork.frame())
            assertThat(motion.frame(NowPlayingBlock.Controls, 1f)).isEqualTo(landscapeControls.frame())
        }
    }

    @Test
    fun `size, place and angle change continuously, never in one step`() {
        for (motion in listOf(turnToLandscape(1), turnToLandscape(3), turnToPortrait(1), turnToPortrait(3))) {
            for (block in listOf(NowPlayingBlock.Artwork, NowPlayingBlock.Controls)) {
                samples.zipWithNext().forEach { (a, b) ->
                    val from = motion.frame(block, a)!!
                    val to = motion.frame(block, b)!!
                    // 1/400 of the turn: a few pixels and well under a degree, nowhere a jump.
                    assertThat((to.center - from.center).getDistance()).isLessThan(15f)
                    assertThat(abs(to.size.width - from.size.width)).isLessThan(2f)
                    assertThat(abs(to.size.height - from.size.height)).isLessThan(2f)
                    assertThat(abs(to.rotation - from.rotation)).isLessThan(0.5f)
                }
            }
        }
    }

    @Test
    fun `the two blocks never overlap on the way, whichever way the phone turns`() {
        for (motion in listOf(turnToLandscape(1), turnToLandscape(3), turnToPortrait(1), turnToPortrait(3))) {
            for (e in samples) {
                val artwork = motion.frame(NowPlayingBlock.Artwork, e)!!
                val controls = motion.frame(NowPlayingBlock.Controls, e)!!
                assertThat(overlaps(artwork, controls)).isFalse()
            }
        }
    }

    @Test
    fun `turning towards the artwork's side keeps each block on its own side of the screen`() {
        // Display 0 -> 90: the artwork was on the top of the display and stays there (the new left half).
        val motion = turnToLandscape(1)
        for (e in samples) {
            assertThat(motion.frame(NowPlayingBlock.Artwork, e)!!.center.x).isLessThan(landscape.width / 2f)
            assertThat(motion.frame(NowPlayingBlock.Controls, e)!!.center.x).isGreaterThan(landscape.width / 2f)
        }
    }

    @Test
    fun `turning the other way, both blocks orbit the same way round, like one body`() {
        // Display 0 -> 270: the artwork starts on the right and must reach the left; the controls swap the other way.
        val motion = turnToLandscape(3)
        val half = 0.5f
        val artwork = motion.frame(NowPlayingBlock.Artwork, half)!!.center - landscape.center
        val controls = motion.frame(NowPlayingBlock.Controls, half)!!.center - landscape.center
        // Half way they are on opposite sides of the centre, one above, one below: they pass, never meet.
        assertThat(artwork.y * controls.y).isLessThan(0f)
        assertThat(abs(artwork.y)).isGreaterThan(abs(artwork.x))
        assertThat(abs(controls.y)).isGreaterThan(abs(controls.x))
    }

    @Test
    fun `a block's content is fitted to the frame, keeping its proportions`() {
        val frame = BlockFrame(Offset.Zero, Size(400f, 300f), 0f)
        assertThat(frame.fitScale(Size(800f, 300f))).isWithin(1e-4f).of(0.5f)
        assertThat(frame.fitScale(Size(400f, 300f))).isWithin(1e-4f).of(1f)
        assertThat(frame.fitScale(Size.Zero)).isEqualTo(1f)
    }

    @Test
    fun `the old picture is drawn at its own size at the start and the live block at its own size at the end`() {
        val motion = turnToLandscape(1)
        assertThat(motion.frame(NowPlayingBlock.Controls, 0f)!!.fitScale(portraitControls.size)).isWithin(1e-4f).of(1f)
        assertThat(motion.frame(NowPlayingBlock.Controls, 1f)!!.fitScale(landscapeControls.size)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `the old picture fades out over the first part of the turn while the new one fades in`() {
        assertThat(snapshotAlpha(0f)).isEqualTo(1f)
        assertThat(liveAlpha(0f, opaque = false)).isEqualTo(0f)
        assertThat(snapshotAlpha(CROSSFADE_END)).isEqualTo(0f)
        assertThat(liveAlpha(CROSSFADE_END, opaque = false)).isEqualTo(1f)
        assertThat(snapshotAlpha(1f)).isEqualTo(0f)
        assertThat(liveAlpha(1f, opaque = false)).isEqualTo(1f)
        samples.zipWithNext().forEach { (a, b) ->
            assertThat(snapshotAlpha(b)).isAtMost(snapshotAlpha(a))
            assertThat(liveAlpha(b, opaque = false)).isAtLeast(liveAlpha(a, opaque = false))
        }
    }

    @Test
    fun `an opaque block waits fully drawn under the old picture instead of fading in`() {
        assertThat(samples.map { liveAlpha(it, opaque = true) }.toSet()).containsExactly(1f)
    }

    @Test
    fun `the easing decelerates to rest without overshooting`() {
        assertThat(turnEasing(0f)).isEqualTo(0f)
        assertThat(turnEasing(1f)).isEqualTo(1f)
        samples.zipWithNext().forEach { (a, b) ->
            assertThat(turnEasing(b)).isAtLeast(turnEasing(a))
            assertThat(turnEasing(b)).isAtMost(1f)
        }
        // Decelerating: the last tenth of the time covers little of the way.
        assertThat(1f - turnEasing(0.9f)).isLessThan(0.02f)
    }

    @Test
    fun `aiming at a new place mid-turn continues from where the blocks are and still ends exactly there`() {
        val motion = turnToLandscape(1)
        val at = 0.3f
        val moved = landscapeControls.translate(0f, 40f)
        val retargeted = motion.retargeted(
            at,
            mapOf(NowPlayingBlock.Artwork to landscapeArtwork.frame(), NowPlayingBlock.Controls to moved.frame()),
        )
        for (block in listOf(NowPlayingBlock.Artwork, NowPlayingBlock.Controls)) {
            val before = motion.frame(block, at)!!
            val after = retargeted.frame(block, at)!!
            assertThat((after.center - before.center).getDistance()).isLessThan(0.01f)
            assertThat(after.rotation).isWithin(1e-3f).of(before.rotation)
            assertThat(after.size.width).isWithin(1e-2f).of(before.size.width)
        }
        assertThat(retargeted.frame(NowPlayingBlock.Controls, 1f)).isEqualTo(moved.frame())
        assertThat(retargeted.frame(NowPlayingBlock.Artwork, 1f)).isEqualTo(landscapeArtwork.frame())
        for (e in samples.filter { it >= at }) {
            assertThat(overlaps(retargeted.frame(NowPlayingBlock.Artwork, e)!!, retargeted.frame(NowPlayingBlock.Controls, e)!!)).isFalse()
        }
    }

    @Test
    fun `a block without a start is not part of the turn`() {
        val motion = TurnMotion(
            starts = mapOf(NowPlayingBlock.Artwork to portraitArtwork.frame()),
            ends = mapOf(NowPlayingBlock.Artwork to landscapeArtwork.frame(), NowPlayingBlock.Controls to landscapeControls.frame()),
            pivot = landscape.center,
        )
        assertThat(motion.frame(NowPlayingBlock.Controls, 0.5f)).isNull()
    }

    /** Whether two turned boxes overlap, by the separating axis test on both boxes' edges. */
    private fun overlaps(a: BlockFrame, b: BlockFrame): Boolean {
        val axes = listOf(a, b).flatMap { frame ->
            val radians = Math.toRadians(frame.rotation.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            listOf(Offset(c, s), Offset(-s, c))
        }
        val gap = b.center - a.center
        return axes.all { axis ->
            val distance = abs(gap.x * axis.x + gap.y * axis.y)
            distance < halfExtent(a, axis) + halfExtent(b, axis) - 0.5f
        }
    }

    private fun halfExtent(frame: BlockFrame, axis: Offset): Float {
        val radians = Math.toRadians(frame.rotation.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return max(0f, abs(frame.size.width / 2f * (c * axis.x + s * axis.y)) + abs(frame.size.height / 2f * (-s * axis.x + c * axis.y)))
    }
}
