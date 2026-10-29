package io.sonora.player.wear.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeadphonesPromptStateTest {

    @Test
    fun `nothing is asked while playback is not waiting for an output`() {
        assertThat(HeadphonesPromptState().isVisible(waitingForOutput = false)).isFalse()
    }

    @Test
    fun `the prompt shows when playback starts waiting for an output`() {
        assertThat(HeadphonesPromptState().isVisible(waitingForOutput = true)).isTrue()
    }

    @Test
    fun `a dismissed prompt stays hidden while playback keeps waiting`() {
        val state = HeadphonesPromptState().dismissed()
        assertThat(state.isVisible(waitingForOutput = true)).isFalse()
        assertThat(state.onWaitingChanged(waitingForOutput = true).isVisible(waitingForOutput = true)).isFalse()
    }

    @Test
    fun `the prompt is asked again after the wait ended and started anew`() {
        val state = HeadphonesPromptState().dismissed().onWaitingChanged(waitingForOutput = false)
        assertThat(state.isVisible(waitingForOutput = true)).isTrue()
    }

    @Test
    fun `dismissing twice changes nothing`() {
        assertThat(HeadphonesPromptState().dismissed().dismissed()).isEqualTo(HeadphonesPromptState().dismissed())
    }
}
