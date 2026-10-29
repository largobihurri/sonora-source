package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import io.sonora.core.designsystem.component.TranslucentAlertRole.Cancel
import io.sonora.core.designsystem.component.TranslucentAlertRole.Confirm
import io.sonora.core.designsystem.component.TranslucentAlertRole.Extra
import org.junit.Test

class TranslucentAlertLayoutTest {

    // A 300 px row with an 8 px gap leaves 146 px a button; 24 px of padding leaves 122 px for its label.
    private fun layout(cancel: Boolean, extra: Boolean, widestLabel: Float) =
        translucentAlertButtonLayout(hasCancel = cancel, hasExtra = extra, widestLabel = widestLabel, rowWidth = 300f, gap = 8f, labelPadding = 24f)

    @Test
    fun `a lone OK fills the row`() {
        assertThat(layout(cancel = false, extra = false, widestLabel = 20f))
            .isEqualTo(TranslucentAlertButtonLayout(stacked = false, order = listOf(Confirm)))
    }

    @Test
    fun `cancel and confirm sit side by side with cancel leading`() {
        assertThat(layout(cancel = true, extra = false, widestLabel = 122f))
            .isEqualTo(TranslucentAlertButtonLayout(stacked = false, order = listOf(Cancel, Confirm)))
    }

    @Test
    fun `a label too wide for half the row stacks the pair with cancel last`() {
        assertThat(layout(cancel = true, extra = false, widestLabel = 123f))
            .isEqualTo(TranslucentAlertButtonLayout(stacked = true, order = listOf(Confirm, Cancel)))
    }

    @Test
    fun `three buttons always stack, actions first and cancel last`() {
        assertThat(layout(cancel = true, extra = true, widestLabel = 10f))
            .isEqualTo(TranslucentAlertButtonLayout(stacked = true, order = listOf(Confirm, Extra, Cancel)))
    }

    @Test
    fun `two actions without cancel follow the pair rule`() {
        assertThat(layout(cancel = false, extra = true, widestLabel = 50f))
            .isEqualTo(TranslucentAlertButtonLayout(stacked = false, order = listOf(Extra, Confirm)))
        assertThat(layout(cancel = false, extra = true, widestLabel = 200f))
            .isEqualTo(TranslucentAlertButtonLayout(stacked = true, order = listOf(Confirm, Extra)))
    }

    @Test
    fun `a lone OK too wide for the row still fills it`() {
        assertThat(layout(cancel = false, extra = false, widestLabel = 400f))
            .isEqualTo(TranslucentAlertButtonLayout(stacked = false, order = listOf(Confirm)))
    }
}
