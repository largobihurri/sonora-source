package io.sonora.core.designsystem.component

import androidx.compose.ui.graphics.TileMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverlayBlurEdgesTest {

    @Test
    fun `the overlay blur repeats the app's edge, so a panel near it samples no fade to transparent`() {
        // S1: with Decal a panel 12 dp from the host's edge sampled a backdrop fading to nothing; the queue blur clamps.
        assertThat(OverlayBlurEdges).isEqualTo(TileMode.Clamp)
    }
}
