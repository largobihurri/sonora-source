package io.sonora.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackdropOnTranslucentTest {

    private val portrait = IntSize(1080, 2400)
    private val landscape = IntSize(2400, 1080)

    @Test
    fun `an upright or upside-down display keeps the box as it is`() {
        assertThat(naturalSize(portrait, 0)).isEqualTo(portrait)
        assertThat(naturalSize(portrait, 2)).isEqualTo(portrait)
    }

    @Test
    fun `a display turned a quarter either way is measured in the natural orientation`() {
        assertThat(naturalSize(landscape, 1)).isEqualTo(portrait)
        assertThat(naturalSize(landscape, 3)).isEqualTo(portrait)
    }

    @Test
    fun `the natural picture, centred and counter-rotated, covers the turned screen exactly as before the turn`() {
        val translucent = Rect(Offset.Zero, Size(portrait.width.toFloat(), portrait.height.toFloat()))
        for (quarters in 1..3) {
            val screen = if (quarters % 2 == 1) Size(2400f, 1080f) else Size(1080f, 2400f)
            // Where the whole portrait picture was on the display, seen from the turned screen: the whole screen.
            val before = translucent.turned(quarters, translucent.size)
            assertThat(before).isEqualTo(Rect(Offset.Zero, screen))
            // The backdrop turns its natural-size picture about the screen centre by the same angle a still block uses.
            assertThat(counterRotation(quarters)).isEqualTo(turnStartFrame(translucent, translucent.size, quarters).rotation)
            assertThat(turnStartFrame(translucent, translucent.size, quarters).center).isEqualTo(before.center)
        }
    }
}
