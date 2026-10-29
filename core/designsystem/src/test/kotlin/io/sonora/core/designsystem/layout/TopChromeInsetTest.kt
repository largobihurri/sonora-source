package io.sonora.core.designsystem.layout

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TopChromeInsetTest {

    @Test
    fun `a page with no chrome above it keeps its own top room`() {
        assertThat(topChromeSpace(own = 52.dp, inset = 0.dp)).isEqualTo(52.dp)
        assertThat(topChromeSpace(own = 0.dp, inset = 0.dp)).isEqualTo(0.dp)
    }

    @Test
    fun `a page whose own room ends above the chrome's end grows to clear it`() {
        assertThat(topChromeSpace(own = 52.dp, inset = 63.5.dp)).isEqualTo(63.5.dp)
        assertThat(topChromeSpace(own = 0.dp, inset = 63.5.dp)).isEqualTo(63.5.dp)
    }

    @Test
    fun `a page whose own room already clears the chrome is left as it is`() {
        assertThat(topChromeSpace(own = 76.dp, inset = 63.5.dp)).isEqualTo(76.dp)
    }
}
