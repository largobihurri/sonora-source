package io.sonora.core.designsystem.component.visualcollection

import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharedArtworkSideTest {

    @Test
    fun `the shared artwork is decoded at the window's shorter side, whichever way it is turned`() {
        assertThat(sharedArtworkSide(IntSize(1080, 2400))).isEqualTo(1080)
        assertThat(sharedArtworkSide(IntSize(2400, 1080))).isEqualTo(1080)
    }

    @Test
    fun `a window not measured yet still asks for a real size`() {
        assertThat(sharedArtworkSide(IntSize.Zero)).isEqualTo(1)
    }
}
