package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GravityFrameTest {

    private val portrait = SafeInsets(left = 0, top = 80, right = 0, bottom = 50) // cutout on top, navigation below

    @Test
    fun `the window's insets map back onto the phone's own portrait edges`() {
        assertThat(naturalInsets(portrait, displayRotation = 0)).isEqualTo(portrait)
        // ROTATION_90: the phone turned anticlockwise, its top edge (the cutout) on the window's left.
        val turned = SafeInsets(left = 80, top = 0, right = 50, bottom = 0)
        assertThat(naturalInsets(turned, displayRotation = 1)).isEqualTo(SafeInsets(left = 0, top = 80, right = 0, bottom = 50))
        // ROTATION_270: turned clockwise, its top edge on the window's right.
        val other = SafeInsets(left = 50, top = 0, right = 80, bottom = 0)
        assertThat(naturalInsets(other, displayRotation = 3)).isEqualTo(SafeInsets(left = 0, top = 80, right = 0, bottom = 50))
        // Upside down.
        assertThat(naturalInsets(SafeInsets(0, 50, 0, 80), displayRotation = 2)).isEqualTo(portrait)
    }

    @Test
    fun `the phone's portrait frame keeps the insets it had upright, so a window turn relays nothing out`() {
        val memory = NaturalInsets()
        // Before the first upright visit: the turned window's insets, mapped back.
        assertThat(memory.padding(SafeInsets(left = 80, top = 24, right = 50, bottom = 0), displayRotation = 1))
            .isEqualTo(SafeInsets(left = 0, top = 80, right = 24, bottom = 50))

        assertThat(memory.padding(portrait, displayRotation = 0)).isEqualTo(portrait)
        // Turned now, the system bars moved, but the frame keeps its upright padding.
        assertThat(memory.padding(SafeInsets(left = 80, top = 24, right = 50, bottom = 0), displayRotation = 1)).isEqualTo(portrait)
    }

    @Test
    fun `the portrait frame turns against the window, so it stays where it was on the screen`() {
        assertThat(frameRotation(0)).isEqualTo(0f)
        assertThat(frameRotation(1)).isEqualTo(-90f)
        assertThat(frameRotation(2)).isEqualTo(-180f)
        assertThat(frameRotation(3)).isEqualTo(-270f)
    }

    @Test
    fun `seen from a turned window, groups level in the phone's frame hang a quarter less`() {
        // Held sideways (right edge up) the groups hang at 90 in the phone's frame; a ROTATION_90 window is level.
        assertThat(windowAngle(90f, displayRotation = 1)).isEqualTo(0f)
        assertThat(dismissQuarter(windowAngle(90f, displayRotation = 1))).isEqualTo(0)
        assertThat(dismissQuarter(windowAngle(-90f, displayRotation = 3))).isEqualTo(0)
        // The same phone frame in a portrait window: the floor is on the screen's left.
        assertThat(dismissQuarter(windowAngle(90f, displayRotation = 0))).isEqualTo(1)
    }

}
