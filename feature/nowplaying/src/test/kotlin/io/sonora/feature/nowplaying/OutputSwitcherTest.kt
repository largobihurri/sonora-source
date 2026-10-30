package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutputSwitcherTest {
    @Test
    fun `Android 14 and later use the public system output switcher, whether or not the settings panel resolves`() {
        assertThat(outputSwitcherFor(sdk = 34, panelResolves = true)).isEqualTo(OutputSwitcher.SYSTEM_SWITCHER)
        assertThat(outputSwitcherFor(sdk = 37, panelResolves = false)).isEqualTo(OutputSwitcher.SYSTEM_SWITCHER)
    }

    @Test
    fun `before Android 14 the settings panel is used when it resolves`() {
        assertThat(outputSwitcherFor(sdk = 33, panelResolves = true)).isEqualTo(OutputSwitcher.SETTINGS_PANEL)
    }

    @Test
    fun `before Android 14 without the settings panel there is no output button`() {
        assertThat(outputSwitcherFor(sdk = 30, panelResolves = false)).isEqualTo(OutputSwitcher.NONE)
    }
}
