package io.sonora.core.designsystem.component.visualcollection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.designsystem.translucent.BubbleFrames
import io.sonora.core.designsystem.translucent.TranslucentMorph
import io.sonora.core.designsystem.translucent.MorphFrame
import io.sonora.core.designsystem.translucent.MorphRoute
import org.junit.Test

class VisualCollectionHandoffTest {

    private val screen = MorphFrame(Rect(0f, 0f, 2400f, 1080f), radius = 0f)
    private val capsuleFrame = MorphFrame.capsule(Rect(2000f, 60f, 2340f, 204f))
    private val thumb = ArtworkPose.flat(Rect(2018f, 78f, 2126f, 186f), radius = 24f)
    private val cover = ArtworkPose(Offset(1200f, 400f), side = 600f, rotationY = 10f)
    private val nowPlaying = ArtworkPose.flat(Rect(100f, 140f, 900f, 940f), radius = 36f)

    private var progress = 0.5f
    private var target: ArtworkPose? = nowPlaying
    private var centreShared = true
    private val owner = Any()

    private val handoff = VisualCollectionHandoff().apply {
        active = true
        progress = { this@VisualCollectionHandoffTest.progress }
        target = { this@VisualCollectionHandoffTest.target }
        placeCapsule(owner, capsuleFrame)
        placeThumbnail(owner, thumb)
        centre = { cover.takeIf { centreShared } }
    }

    private fun bubbled(): VisualCollectionHandoff = handoff.apply {
        bubble = { BubbleFrames(TranslucentMorph.straight(this@VisualCollectionHandoffTest.progress, capsuleFrame, screen), capsuleFrame, screen) }
    }

    @Test
    fun `the artwork flies only while a hand-off is under way between its two ends`() {
        assertThat(handoff.flying()).isTrue()
        progress = 0f
        assertThat(handoff.flying()).isFalse()
        progress = 1f
        assertThat(handoff.flying()).isFalse()
        progress = 0.5f
        handoff.active = false
        assertThat(handoff.flying()).isFalse()
        assertThat(handoff.flight()).isNull()
    }

    @Test
    fun `nothing flies without somewhere to land or somewhere to leave from`() {
        target = null // lyrics or gravity mode: Now Playing shows no artwork
        assertThat(handoff.flying()).isFalse()
        target = nowPlaying
        centreShared = false
        handoff.removeThumbnail(owner)
        assertThat(handoff.flying()).isFalse()
    }

    @Test
    fun `on its own, the shared centre cover flies before the capsule's thumbnail`() {
        assertThat(handoff.source()).isEqualTo(cover)
        assertThat(handoff.flight()).isEqualTo(ArtworkFlight.pose(0.5f, cover, nowPlaying))
        centreShared = false
        assertThat(handoff.source()).isEqualTo(thumb)
        assertThat(handoff.flight()).isEqualTo(ArtworkFlight.pose(0.5f, thumb, nowPlaying))
    }

    @Test
    fun `the visual collection hides its centre cover only while that cover is the one flying`() {
        assertThat(handoff.hidesCentre()).isTrue()
        centreShared = false
        handoff.centre = null
        assertThat(handoff.hidesCentre()).isFalse()
        handoff.centre = { cover }
        progress = 1f
        assertThat(handoff.hidesCentre()).isFalse()
    }

    @Test
    fun `in a bubble the thumbnail is carried inside it and the centre cover stays where it is`() {
        val bubbled = bubbled()
        assertThat(bubbled.source()).isEqualTo(thumb)
        assertThat(bubbled.hidesCentre()).isFalse()
        val frames = BubbleFrames(TranslucentMorph.straight(0.5f, capsuleFrame, screen), capsuleFrame, screen)
        assertThat(bubbled.flight()).isEqualTo(ArtworkFlight.carried(frames, thumb, nowPlaying))
    }

    @Test
    fun `a bubble without frames carries nothing, so neither screen hides its artwork`() {
        val bubbled = bubbled().apply { bubble = { null } }
        assertThat(bubbled.flying()).isFalse()
        assertThat(bubbled.flight()).isNull()
    }

    @Test
    fun `the capsule fades out of the way only during a hand-off`() {
        assertThat(handoff.capsuleAlpha()).isEqualTo(TranslucentMorph.capsuleAlpha(0.5f))
        progress = 0f
        assertThat(handoff.capsuleAlpha()).isEqualTo(1f)
        progress = 0.5f
        handoff.active = false
        assertThat(handoff.capsuleAlpha()).isEqualTo(1f)
    }

    @Test
    fun `a capsule that leaves takes its frames with it, and leaves another capsule's alone`() {
        assertThat(handoff.capsule).isEqualTo(capsuleFrame)
        val other = Any()
        handoff.removeCapsule(other)
        handoff.removeThumbnail(other)
        assertThat(handoff.capsule).isEqualTo(capsuleFrame)
        handoff.removeCapsule(owner)
        handoff.removeThumbnail(owner)
        assertThat(handoff.capsule).isNull()
        centreShared = false
        assertThat(handoff.source()).isNull()
    }

    @Test
    fun `a capsule placed again by another owner is that owner's to remove`() {
        val other = Any()
        handoff.placeCapsule(other, capsuleFrame)
        handoff.removeCapsule(owner)
        assertThat(handoff.capsule).isEqualTo(capsuleFrame)
        handoff.removeCapsule(other)
        assertThat(handoff.capsule).isNull()
    }

    @Test
    fun `exactly one artwork shows at every step of a bubble's opening and of its dismissal`() {
        // T67.7: the capsule's thumbnail, the carried artwork and Now Playing's own (hidden while the artwork flies, see
        // ArtworkShare.hidden) hand over at once at each end, never fading in parallel.
        for (route in MorphRoute.entries) {
            handoff.bubble = {
                BubbleFrames(TranslucentMorph.frame(route, this@VisualCollectionHandoffTest.progress, capsuleFrame, screen, 324f), capsuleFrame, screen)
            }
            for (step in 0..200) {
                progress = step / 200f
                val thumbnail = handoff.capsuleAlpha() * handoff.thumbnailAlpha()
                val carried = if (handoff.flying()) 1f else 0f
                val nowPlayingOwn = if (progress > 0f && !handoff.flying()) 1f else 0f
                val shown = listOf(thumbnail, carried, nowPlayingOwn).count { it > 0f }
                assertWithMessage("$route at $progress: thumbnail $thumbnail, carried $carried, Now Playing's $nowPlayingOwn")
                    .that(shown).isEqualTo(1)
            }
        }
    }

    @Test
    fun `the thumbnail stays when the centre cover is the one flying on its own`() {
        progress = 0.5f
        assertThat(handoff.flying()).isTrue()
        assertThat(handoff.thumbnailAlpha()).isEqualTo(1f)
        centreShared = false
        assertThat(handoff.thumbnailAlpha()).isEqualTo(0f)
    }
}
