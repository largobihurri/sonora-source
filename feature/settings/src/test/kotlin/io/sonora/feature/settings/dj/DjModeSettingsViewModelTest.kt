package io.sonora.feature.settings.dj

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.conversion.PerformanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test

class DjModeSettingsViewModelTest {

    private class FakeSettings(on: Boolean = false) : DjModeSettings {
        private val state = MutableStateFlow(on)
        override val enabled: StateFlow<Boolean> = state

        override fun setEnabled(enabled: Boolean) {
            state.value = enabled
        }

        private val mode = MutableStateFlow(PerformanceMode.NORMAL)
        override val performance: StateFlow<PerformanceMode> = mode

        override fun setPerformance(performance: PerformanceMode) {
            mode.value = performance
        }
    }

    @Test
    fun `the switch shows the stored state`() {
        assertThat(DjModeSettingsViewModel(FakeSettings(on = false)).enabled.value).isFalse()
        assertThat(DjModeSettingsViewModel(FakeSettings(on = true)).enabled.value).isTrue()
    }

    @Test
    fun `flipping the switch stores it and the view model follows`() {
        val settings = FakeSettings()
        val viewModel = DjModeSettingsViewModel(settings)

        viewModel.setEnabled(true)
        assertThat(settings.enabled.value).isTrue()
        assertThat(viewModel.enabled.value).isTrue()

        viewModel.setEnabled(false)
        assertThat(settings.enabled.value).isFalse()
        assertThat(viewModel.enabled.value).isFalse()
    }

    @Test
    fun `the performance selector shows the stored mode, normal by default`() {
        assertThat(DjModeSettingsViewModel(FakeSettings()).performance.value).isEqualTo(PerformanceMode.NORMAL)
    }

    @Test
    fun `choosing a performance stores it and the view model follows`() {
        val settings = FakeSettings()
        val viewModel = DjModeSettingsViewModel(settings)

        viewModel.setPerformance(PerformanceMode.LOW_RESOURCES)

        assertThat(settings.performance.value).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(viewModel.performance.value).isEqualTo(PerformanceMode.LOW_RESOURCES)
    }
}
