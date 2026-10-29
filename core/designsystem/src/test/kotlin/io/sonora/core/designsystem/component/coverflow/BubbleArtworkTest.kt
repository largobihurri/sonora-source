package io.sonora.core.designsystem.component.coverflow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.designsystem.translucent.BubbleFrames
import io.sonora.core.designsystem.translucent.TranslucentMorph
import io.sonora.core.designsystem.translucent.MorphFrame
import io.sonora.core.designsystem.translucent.MorphRoute
import org.junit.Test

/** T67.4: the shared artwork is carried inside the translucent bubble, so the two move as one. */
class BubbleArtworkTest {

    /** A screen, the capsule Now Playing grows out of, the capsule's thumbnail and Now Playing's artwork. */
    private class Scene(val screen: MorphFrame, val capsule: MorphFrame, val thumbnail: ArtworkPose, val slot: ArtworkPose) {
        val push = screen.rect.height * TranslucentMorph.ARC_PUSH_FRACTION

        fun frames(route: MorphRoute, progress: Float) =
            BubbleFrames(TranslucentMorph.frame(route, progress, capsule, screen, push), capsule, screen)

        /** The thumbnail's gap to the capsule's top and bottom. */
        val inset = (capsule.rect.height - thumbnail.side) / 2f
    }

    // Landscape: the Cover Flow's capsule at the top right, Now Playing's artwork on the left half.
    private val coverFlow = Scene(
        screen = MorphFrame(Rect(0f, 0f, 2400f, 1080f), radius = 0f),
        capsule = MorphFrame.capsule(Rect(2000f, 60f, 2340f, 204f)),
        thumbnail = ArtworkPose.flat(Rect(2018f, 78f, 2126f, 186f), radius = 24f),
        slot = ArtworkPose.flat(Rect(100f, 140f, 900f, 940f), radius = 36f),
    )

    // Portrait: the mini player above the tab bar, Now Playing's artwork at the top.
    private val miniPlayer = Scene(
        screen = MorphFrame(Rect(0f, 0f, 1080f, 2400f), radius = 0f),
        capsule = MorphFrame.capsule(Rect(48f, 1980f, 1032f, 2148f)),
        thumbnail = ArtworkPose.flat(Rect(72f, 2004f, 192f, 2124f), radius = 24f),
        slot = ArtworkPose.flat(Rect(90f, 330f, 990f, 1230f), radius = 36f),
    )

    private fun assertPose(actual: ArtworkPose, expected: ArtworkPose) {
        assertThat(actual.centre.x).isWithin(0.01f).of(expected.centre.x)
        assertThat(actual.centre.y).isWithin(0.01f).of(expected.centre.y)
        assertThat(actual.side).isWithin(0.01f).of(expected.side)
        assertThat(actual.radius).isWithin(0.01f).of(expected.radius)
        assertThat(actual.rotationY).isWithin(0.01f).of(expected.rotationY)
    }

    @Test
    fun `in the capsule it is the capsule's thumbnail, at full screen Now Playing's artwork`() {
        for (scene in listOf(coverFlow, miniPlayer)) {
            for (route in MorphRoute.entries) {
                assertPose(ArtworkFlight.carried(scene.frames(route, 0f), scene.thumbnail, scene.slot), scene.thumbnail)
                assertPose(ArtworkFlight.carried(scene.frames(route, 1f), scene.thumbnail, scene.slot), scene.slot)
            }
        }
    }

    @Test
    fun `it stays inside the bubble at every step of both routes`() {
        for (scene in listOf(coverFlow, miniPlayer)) {
            for (route in MorphRoute.entries) {
                for (step in 0..200) {
                    val frames = scene.frames(route, step / 200f)
                    val pose = ArtworkFlight.carried(frames, scene.thumbnail, scene.slot)
                    val bubble = frames.bubble.rect
                    val half = pose.side / 2f
                    val where = "$route at ${step / 200f}: $pose in $bubble"
                    assertWithMessage(where).that(pose.side).isGreaterThan(0f)
                    assertWithMessage(where).that(pose.centre.x - half).isAtLeast(bubble.left - 0.01f)
                    assertWithMessage(where).that(pose.centre.x + half).isAtMost(bubble.right + 0.01f)
                    assertWithMessage(where).that(pose.centre.y - half).isAtLeast(bubble.top - 0.01f)
                    assertWithMessage(where).that(pose.centre.y + half).isAtMost(bubble.bottom + 0.01f)
                    assertWithMessage(where).that(pose.radius).isAtMost(half + 0.01f)
                }
            }
        }
    }

    @Test
    fun `it is placed by the bubble, so it follows the bubble wherever the bubble goes`() {
        val frames = coverFlow.frames(MorphRoute.Arc, 0.4f)
        val pushed = frames.copy(bubble = MorphFrame(frames.bubble.rect.translate(30f, 120f), frames.bubble.radius))
        val before = ArtworkFlight.carried(frames, coverFlow.thumbnail, coverFlow.slot)
        val after = ArtworkFlight.carried(pushed, coverFlow.thumbnail, coverFlow.slot)
        assertThat(after.centre.x).isWithin(0.01f).of(before.centre.x + 30f)
        assertThat(after.centre.y).isWithin(0.01f).of(before.centre.y + 120f)
        assertThat(after.side).isWithin(0.01f).of(before.side)
    }

    @Test
    fun `it grows with the bubble`() {
        for (scene in listOf(coverFlow, miniPlayer)) {
            var last = 0f
            for (step in 0..100) {
                val side = ArtworkFlight.carried(scene.frames(MorphRoute.Straight, step / 100f), scene.thumbnail, scene.slot).side
                assertThat(side).isAtLeast(last - 0.01f)
                last = side
            }
        }
    }

    @Test
    fun `landing in the mini player, it keeps the thumbnail's share of the low, wide card's height`() {
        // T67.6: Now Playing drawn to cover a low, wide card is far wider than the card; its artwork must not swell
        // the carried one to the card's full height on the last stretch of the dismissal.
        val share = miniPlayer.thumbnail.side / miniPlayer.capsule.rect.height
        for (progress in listOf(0f, 0.02f, 0.04f, 0.06f, 0.09f)) {
            val frames = miniPlayer.frames(MorphRoute.Arc, progress)
            val pose = ArtworkFlight.carried(frames, miniPlayer.thumbnail, miniPlayer.slot)
            assertWithMessage("at $progress: $pose in ${frames.bubble.rect}")
                .that(pose.side / frames.bubble.rect.height).isWithin(0.05f).of(share)
        }
    }

    @Test
    fun `dismissed, it stays inside the bubble with the thumbnail's inset, shrinking with it`() {
        // T67.6, device check 2: mid-dismissal the artwork filled the card's height and stuck out above its top.
        for (scene in listOf(miniPlayer, coverFlow)) {
            for (step in 0..50) {
                val frames = scene.frames(MorphRoute.Arc, step / 50f)
                val pose = ArtworkFlight.carried(frames, scene.thumbnail, scene.slot)
                val inner = frames.bubble.rect.deflate(scene.inset)
                val half = pose.side / 2f
                val where = "at ${step / 50f}: $pose in ${frames.bubble.rect}"
                assertWithMessage(where).that(pose.side).isAtMost(frames.bubble.rect.height - 2f * scene.inset + 0.01f)
                assertWithMessage(where).that(pose.centre.x - half).isAtLeast(inner.left - 0.01f)
                assertWithMessage(where).that(pose.centre.x + half).isAtMost(inner.right + 0.01f)
                assertWithMessage(where).that(pose.centre.y - half).isAtLeast(inner.top - 0.01f)
                assertWithMessage(where).that(pose.centre.y + half).isAtMost(inner.bottom + 0.01f)
            }
        }
    }

    @Test
    fun `dismissed into the mini player, it heads for the thumbnail's place, its share of the card's height easing`() {
        val scene = miniPlayer
        val thumbShare = scene.thumbnail.side / scene.capsule.rect.height
        val slotShare = scene.slot.side / scene.screen.rect.height
        var lastLead = Float.MAX_VALUE
        for (step in 50 downTo 0) {
            val frames = scene.frames(MorphRoute.Arc, step / 50f)
            val box = frames.bubble.rect
            val pose = ArtworkFlight.carried(frames, scene.thumbnail, scene.slot)
            val open = TranslucentMorph.openness(frames.bubble, frames.capsule, frames.screen)
            val where = "at ${step / 50f}: $pose in $box"
            // Its share of the card's height moves from Now Playing's to the thumbnail's as the card shrinks.
            assertWithMessage(where).that(pose.side / box.height)
                .isWithin(0.01f).of(slotShare + (thumbShare - slotShare) * (1f - open))
            // Its leading gap only closes in on the thumbnail's.
            val lead = pose.centre.x - pose.side / 2f - box.left
            assertWithMessage(where).that(lead).isAtMost(lastLead + 0.01f)
            lastLead = lead
        }
        // On the last stretch it sits where the thumbnail lands: at the leading edge, vertically centred in the card.
        val frames = scene.frames(MorphRoute.Arc, 0.2f)
        val pose = ArtworkFlight.carried(frames, scene.thumbnail, scene.slot)
        assertThat(pose.centre.y).isWithin(0.05f * frames.bubble.rect.height).of(frames.bubble.rect.center.y)
        assertThat(pose.centre.x - pose.side / 2f - frames.bubble.rect.left)
            .isWithin(0.1f * frames.bubble.rect.width).of(scene.thumbnail.centre.x - scene.thumbnail.side / 2f - scene.capsule.rect.left)
    }

    @Test
    fun `a bubble with no room carries an empty artwork at its centre`() {
        val frames = BubbleFrames(MorphFrame(Rect(Offset(500f, 500f), Offset(500f, 500f)), 0f), coverFlow.capsule, coverFlow.screen)
        val pose = ArtworkFlight.carried(frames, coverFlow.thumbnail, coverFlow.slot)
        assertThat(pose.side).isEqualTo(0f)
        assertThat(pose.centre).isEqualTo(Offset(500f, 500f))
    }

    @Test
    fun `opening carries the artwork as a dismissal does at the same bubble size, played backwards`() {
        for (scene in listOf(miniPlayer, coverFlow)) {
            for (step in 0..100) {
                val open = step / 100f
                val a = scene.frames(MorphRoute.Straight, open)
                val b = scene.frames(MorphRoute.Arc, open) // both routes: openness == progress (T67.8)
                val pa = ArtworkFlight.carried(a, scene.thumbnail, scene.slot)
                val pb = ArtworkFlight.carried(b, scene.thumbnail, scene.slot)
                val where = "openness $open: $pa in ${a.bubble.rect} vs $pb in ${b.bubble.rect}"
                // The same place in the bubble, whichever way the bubble itself travels.
                assertWithMessage(where).that(pa.centre.x - a.bubble.rect.left).isWithin(1f).of(pb.centre.x - b.bubble.rect.left)
                assertWithMessage(where).that(pa.centre.y - a.bubble.rect.top).isWithin(1f).of(pb.centre.y - b.bubble.rect.top)
                assertWithMessage(where).that(pa.side).isWithin(1f).of(pb.side)
                assertWithMessage(where).that(pa.radius).isWithin(1f).of(pb.radius)
            }
        }
    }

    @Test
    fun `opening, it stays inside the bubble with the thumbnail's inset, growing with it`() {
        for (scene in listOf(miniPlayer, coverFlow)) {
            for (step in 0..50) {
                val frames = scene.frames(MorphRoute.Straight, step / 50f)
                val pose = ArtworkFlight.carried(frames, scene.thumbnail, scene.slot)
                val inner = frames.bubble.rect.deflate(scene.inset)
                val half = pose.side / 2f
                val where = "at ${step / 50f}: $pose in ${frames.bubble.rect}"
                assertWithMessage(where).that(pose.side).isAtMost(frames.bubble.rect.height - 2f * scene.inset + 0.01f)
                assertWithMessage(where).that(pose.centre.x - half).isAtLeast(inner.left - 0.01f)
                assertWithMessage(where).that(pose.centre.x + half).isAtMost(inner.right + 0.01f)
                assertWithMessage(where).that(pose.centre.y - half).isAtLeast(inner.top - 0.01f)
                assertWithMessage(where).that(pose.centre.y + half).isAtMost(inner.bottom + 0.01f)
            }
        }
    }

    // The mini player as laid out: a 60 dp capsule whose 40 dp thumbnail starts 8 dp from its round end, nearer than
    // its 10 dp gap to the top and bottom (2.75 px per dp).
    private val laidOutMiniPlayer = Scene(
        screen = MorphFrame(Rect(0f, 0f, 1080f, 2376f), radius = 0f),
        capsule = MorphFrame.capsule(Rect(44f, 1937.5f, 1036f, 2102.5f)),
        thumbnail = ArtworkPose.flat(Rect(66f, 1965f, 176f, 2075f), radius = 22f),
        slot = ArtworkPose.flat(Rect(77f, 330f, 1003f, 1256f), radius = 33f),
    )

    @Test
    fun `it leaves from and lands on the thumbnail exactly, whatever its gaps in the capsule`() {
        // T67.8, device: the carried artwork landed 8 px right of the real thumbnail and jumped at the hand-off.
        for (scene in listOf(laidOutMiniPlayer, miniPlayer, coverFlow)) {
            for (route in MorphRoute.entries) {
                // At rest closed exactly, and within about half a dp on the spring's last step (a thousandth of the way).
                for ((progress, within) in listOf(0f to 0.01f, 0.0005f to 1.5f)) {
                    val pose = ArtworkFlight.carried(scene.frames(route, progress), scene.thumbnail, scene.slot)
                    val where = "$route at $progress: $pose vs ${scene.thumbnail}"
                    assertWithMessage(where).that(pose.centre.x).isWithin(within).of(scene.thumbnail.centre.x)
                    assertWithMessage(where).that(pose.centre.y).isWithin(within).of(scene.thumbnail.centre.y)
                    assertWithMessage(where).that(pose.side).isWithin(within).of(scene.thumbnail.side)
                }
                assertPose(ArtworkFlight.carried(scene.frames(route, 1f), scene.thumbnail, scene.slot), scene.slot)
            }
        }
    }

    @Test
    fun `fully open it is Now Playing's artwork exactly, even one nearer the screen's edges than the thumbnail's gap`() {
        // Review R3-001 of 1db4c6cb: the thumbnail's inset must not shrink or move a near-edge artwork at the first
        // frame of a dismissal (or the last of an opening).
        val wide = Scene(
            screen = miniPlayer.screen,
            capsule = miniPlayer.capsule,
            thumbnail = miniPlayer.thumbnail,
            slot = ArtworkPose.flat(Rect(8f, 300f, 1072f, 1364f), radius = 36f),
        )
        val tall = Scene(
            screen = coverFlow.screen,
            capsule = coverFlow.capsule,
            thumbnail = coverFlow.thumbnail,
            slot = ArtworkPose.flat(Rect(100f, 6f, 1168f, 1074f), radius = 36f),
        )
        for (scene in listOf(wide, tall, miniPlayer, coverFlow, laidOutMiniPlayer)) {
            for (route in MorphRoute.entries) {
                assertPose(ArtworkFlight.carried(scene.frames(route, 1f), scene.thumbnail, scene.slot), scene.slot)
                // And a step short of open it is within a pixel of it, so nothing jumps.
                val near = ArtworkFlight.carried(scene.frames(route, 0.999f), scene.thumbnail, scene.slot)
                val box = scene.frames(route, 0.999f).bubble.rect
                assertWithMessage("$route near open: $near in $box").that(near.side).isWithin(3f).of(scene.slot.side)
            }
        }
    }

    /** [scene] laid out right to left: everything mirrored about the screen's vertical centre line. */
    private fun mirrored(scene: Scene): Scene {
        val w = scene.screen.rect.right + scene.screen.rect.left
        fun Rect.flip() = Rect(w - right, top, w - left, bottom)
        fun ArtworkPose.flip() = copy(centre = Offset(w - centre.x, centre.y), rotationY = -rotationY)
        return Scene(
            screen = scene.screen.copy(rect = scene.screen.rect.flip()),
            capsule = scene.capsule.copy(rect = scene.capsule.rect.flip()),
            thumbnail = scene.thumbnail.flip(),
            slot = scene.slot.flip(),
        )
    }

    @Test
    fun `right to left, the artwork tracks the thumbnail at the capsule's right end, mirroring left to right`() {
        // S3: the lead was measured from the capsule's left, so in RTL (thumbnail at the right) the carried artwork
        // drifted away from the trailing side it starts and ends on.
        for (scene in listOf(laidOutMiniPlayer, miniPlayer, coverFlow)) {
            val rtl = mirrored(scene)
            for (route in MorphRoute.entries) {
                for (step in 0..50) {
                    val p = step / 50f
                    val ltrFrames = scene.frames(route, p)
                    val rtlFrames = rtl.frames(route, p)
                    val ltr = ArtworkFlight.carried(ltrFrames, scene.thumbnail, scene.slot)
                    val pose = ArtworkFlight.carried(rtlFrames, rtl.thumbnail, rtl.slot)
                    val where = "$route at $p: $pose in ${rtlFrames.bubble.rect} vs $ltr in ${ltrFrames.bubble.rect}"
                    assertWithMessage(where).that(rtlFrames.bubble.rect.right - pose.centre.x)
                        .isWithin(0.5f).of(ltr.centre.x - ltrFrames.bubble.rect.left)
                    assertWithMessage(where).that(pose.centre.y - rtlFrames.bubble.rect.top)
                        .isWithin(0.5f).of(ltr.centre.y - ltrFrames.bubble.rect.top)
                    assertWithMessage(where).that(pose.side).isWithin(0.5f).of(ltr.side)
                }
                assertPose(ArtworkFlight.carried(rtl.frames(route, 0f), rtl.thumbnail, rtl.slot), rtl.thumbnail)
                assertPose(ArtworkFlight.carried(rtl.frames(route, 1f), rtl.thumbnail, rtl.slot), rtl.slot)
            }
        }
    }
}
