package io.sonora.feature.settings.dub

import com.google.common.truth.Truth.assertThat
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.PadBehaviour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Settings › DUB Mode: the switch is the app-wide flag, Pads is stored in the setup, and the reset clears the setup. */
@OptIn(ExperimentalCoroutinesApi::class)
class DubModeSettingsViewModelTest {

    private class FakeMode : DubModeSettings {
        override val enabled = MutableStateFlow(false)
        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    private class FakeSetups : DubSetupRepository {
        override val setup = MutableStateFlow(DubSetup())
        override suspend fun update(transform: (DubSetup) -> DubSetup) {
            setup.value = transform(setup.value)
        }
        override fun preview(setup: DubSetup) = Unit
    }

    private val mode = FakeMode()
    private val setups = FakeSetups()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the switch is bound to the DUB mode flag`() = runTest {
        val vm = DubModeSettingsViewModel(mode, setups)
        assertThat(vm.enabled.value).isFalse()
        vm.setEnabled(true)
        assertThat(mode.enabled.value).isTrue()
        assertThat(vm.enabled.value).isTrue()
    }

    @Test
    fun `Pads is stored in the setup`() = runTest {
        val vm = DubModeSettingsViewModel(mode, setups)
        vm.setPadBehaviour(PadBehaviour.TOGGLE)
        assertThat(setups.setup.value.padBehaviour).isEqualTo(PadBehaviour.TOGGLE)
        assertThat(vm.padBehaviour.value).isEqualTo(PadBehaviour.TOGGLE)
    }

    @Test
    fun `reset puts the whole effect setup back to its defaults and leaves the switch`() = runTest {
        mode.setEnabled(true)
        setups.update { it.copy(division = DubDivision.HALF, feedback = 0.9f, padBehaviour = PadBehaviour.TOGGLE) }
        val vm = DubModeSettingsViewModel(mode, setups)
        vm.resetSetup()
        assertThat(setups.setup.value).isEqualTo(DubSetup())
        assertThat(mode.enabled.value).isTrue()
    }
}
