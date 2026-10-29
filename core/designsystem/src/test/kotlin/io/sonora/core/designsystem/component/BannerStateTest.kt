package io.sonora.core.designsystem.component

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BannerStateTest {

    private val icon = ImageVector.Builder("t", 24.dp, 24.dp, 24f, 24f).build()

    @Test
    fun `a newer message replaces the shown one and survives the older one's timeout`() {
        val state = BannerState()
        state.show("Playing Next", icon)
        val first = state.message!!
        state.show("Added to Queue", icon)

        state.dismiss(first)

        assertThat(state.message?.text).isEqualTo("Added to Queue")
        assertThat(state.message?.id).isNotEqualTo(first.id)
    }

    @Test
    fun `dismissing the shown message hides it but keeps it for the exit animation`() {
        val state = BannerState()
        state.show("Added to Queue", icon)

        state.dismiss(state.message!!)

        assertThat(state.message).isNull()
        assertThat(state.lastShown?.text).isEqualTo("Added to Queue")
    }

    @Test
    fun `repeating the same text restarts as a new message`() {
        val state = BannerState()
        state.show("Added to Queue", icon)
        val first = state.message
        state.show("Added to Queue", icon)

        assertThat(state.message).isNotEqualTo(first)
    }

    @Test
    fun `the banner counts as on screen from the first message until its exit animation has finished`() {
        val state = BannerState()
        assertThat(state.onScreen).isFalse()

        state.show("Added to Queue", icon)
        assertThat(state.onScreen).isTrue()

        state.dismiss(state.message!!)
        // Nothing animated it in yet (no composition here), so it is idle and hidden again.
        assertThat(state.onScreen).isFalse()
    }
}
