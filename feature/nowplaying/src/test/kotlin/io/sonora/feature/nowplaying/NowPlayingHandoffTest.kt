package io.sonora.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NowPlayingHandoffTest {

    // The landscape artwork block, in the stage's coordinates, of a Now Playing placed 30 px down in the root.
    private val place = Rect(100f, 140f, 900f, 940f)
    private val origin = Offset(0f, 30f)

    @Test
    fun `playing, the flight lands on the artwork block where it is in the root`() {
        val pose = sharedArtworkPose(place, origin, scale = 1f, radius = 36f)
        assertThat(pose.centre).isEqualTo(Offset(500f, 570f))
        assertThat(pose.side).isEqualTo(800f)
        assertThat(pose.rotationY).isEqualTo(0f)
        assertThat(pose.radius).isEqualTo(36f)
    }

    @Test
    fun `paused, it lands on the artwork shrunk about its centre, corners and all`() {
        val pose = sharedArtworkPose(place, origin, scale = 0.8f, radius = 36f)
        assertThat(pose.centre).isEqualTo(Offset(500f, 570f))
        assertThat(pose.side).isWithin(0.01f).of(640f)
        assertThat(pose.radius).isWithin(0.01f).of(28.8f)
    }
}
