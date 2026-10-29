package io.sonora.feature.nowplaying

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BottomButtonSideTest {

    @Test
    fun `at full size a bottom button is 48 dp square`() {
        assertThat(bottomButtonSide(scale = 1f)).isEqualTo(48.dp)
    }

    @Test
    fun `in the landscape block drawn at 90 percent a button still spans 44 dp on screen`() {
        assertThat(bottomButtonSide(scale = 0.9f).value * 0.9f).isAtLeast(44f)
    }

    @Test
    fun `a block drawn at half size doubles the button to keep 44 dp on screen`() {
        assertThat(bottomButtonSide(scale = 0.5f).value * 0.5f).isAtLeast(44f)
    }
}
