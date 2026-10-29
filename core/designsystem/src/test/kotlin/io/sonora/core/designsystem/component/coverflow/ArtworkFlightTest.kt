package io.sonora.core.designsystem.component.coverflow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class ArtworkFlightTest {

    private val geometry = CoverFlowGeometry(coverSize = 600f)
    private val slot = Rect(900f, 50f, 1500f, 650f)

    // Now Playing's artwork, flat with rounded corners, on the left half of a landscape screen.
    private val nowPlaying = ArtworkPose.flat(Rect(100f, 140f, 900f, 940f), radius = 36f)

    @Test
    fun `a flat artwork is the square centred in its place`() {
        val pose = ArtworkPose.flat(Rect(0f, 0f, 200f, 100f), radius = 8f)
        assertThat(pose.centre).isEqualTo(Offset(100f, 50f))
        assertThat(pose.side).isEqualTo(100f)
        assertThat(pose.rotationY).isEqualTo(0f)
        assertThat(pose.radius).isEqualTo(8f)
    }

    @Test
    fun `the settled centre cover is its slot, facing the viewer`() {
        val pose = ArtworkPose.cover(slot, geometry.transform(0f))
        assertThat(pose.centre).isEqualTo(slot.center)
        assertThat(pose.side).isEqualTo(600f)
        assertThat(pose.rotationY).isEqualTo(0f)
    }

    @Test
    fun `a centre cover caught between two places keeps its turn, shrink and shift`() {
        val transform = geometry.transform(0.4f)
        val pose = ArtworkPose.cover(slot, transform)
        assertThat(pose.centre.x).isWithin(0.01f).of(slot.center.x + transform.translationX)
        assertThat(pose.centre.y).isWithin(0.01f).of(slot.center.y + transform.translationY)
        assertThat(pose.side).isWithin(0.01f).of(600f * transform.scale)
        assertThat(pose.rotationY).isEqualTo(transform.rotationY)
        assertThat(pose.rotationY).isNotEqualTo(0f)
    }

    @Test
    fun `the flight starts on the cover and lands on Now Playing's artwork`() {
        val cover = ArtworkPose.cover(slot, geometry.transform(0.4f))
        assertThat(ArtworkFlight.pose(0f, cover, nowPlaying)).isEqualTo(cover)
        assertThat(ArtworkFlight.pose(1f, cover, nowPlaying)).isEqualTo(nowPlaying)
        assertThat(ArtworkFlight.pose(-1f, cover, nowPlaying)).isEqualTo(cover)
        assertThat(ArtworkFlight.pose(2f, cover, nowPlaying)).isEqualTo(nowPlaying)
    }

    @Test
    fun `it flies straight, growing as it goes`() {
        val cover = ArtworkPose.cover(slot, geometry.transform(0f))
        val half = ArtworkFlight.pose(0.5f, cover, nowPlaying)
        assertThat(half.centre.x).isWithin(0.01f).of((cover.centre.x + nowPlaying.centre.x) / 2f)
        assertThat(half.centre.y).isWithin(0.01f).of((cover.centre.y + nowPlaying.centre.y) / 2f)
        assertThat(half.side).isWithin(0.01f).of((cover.side + nowPlaying.side) / 2f)
        assertThat(half.radius).isWithin(0.01f).of(18f)
    }

    @Test
    fun `it flattens out of the Cover Flow's 3D pose in the first half of the flight`() {
        val cover = ArtworkPose.cover(slot, geometry.transform(0.4f))
        val early = ArtworkFlight.pose(0.2f, cover, nowPlaying)
        assertThat(abs(early.rotationY)).isGreaterThan(0f)
        assertThat(abs(early.rotationY)).isLessThan(abs(cover.rotationY))
        assertThat(ArtworkFlight.pose(0.5f, cover, nowPlaying).rotationY).isEqualTo(0f)
        assertThat(ArtworkFlight.pose(0.8f, cover, nowPlaying).rotationY).isEqualTo(0f)
    }

    @Test
    fun `the centre cover is the shared artwork only when it is the playing album's`() {
        val art = "content://art/1"
        assertThat(ArtworkFlight.centreIsShared(listOf(art), art)).isTrue()
        assertThat(ArtworkFlight.centreIsShared(listOf("content://art/2"), art)).isFalse()
        // An artist's or a playlist's mosaic is never the playing album's cover; nor is a cover without artwork.
        assertThat(ArtworkFlight.centreIsShared(listOf(art, "content://art/2"), art)).isFalse()
        assertThat(ArtworkFlight.centreIsShared(emptyList(), null)).isFalse()
        assertThat(ArtworkFlight.centreIsShared(listOf(art), null)).isFalse()
    }
}
