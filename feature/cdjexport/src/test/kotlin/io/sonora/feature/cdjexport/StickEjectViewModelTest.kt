package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.RemovableVolume.State
import io.sonora.feature.cdjexport.export.EjectScreen
import io.sonora.feature.cdjexport.export.EjectState
import io.sonora.feature.cdjexport.export.StickEject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class StickEjectViewModelTest {

    private val volumes = FakeRemovableVolumes()
    private lateinit var viewModel: StickEjectViewModel

    private val state get() = viewModel.state.value

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = StickEjectViewModel(volumes)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an eject waits for Android to unmount the stick, then says it is safe to remove`() {
        val stick = RemovableVolume("2C9C-2151", "SONORA", 16L shl 30, State.MOUNTED)
        volumes.current.value = listOf(stick)

        viewModel.eject("2C9C-2151")
        assertThat(state).isEqualTo(EjectState.OpenScreen(stick, StickEject.screensFor(stick)))

        viewModel.onScreenOpened(EjectScreen.VOLUME_SETTINGS)
        assertThat(state).isEqualTo(EjectState.Waiting(stick, EjectScreen.VOLUME_SETTINGS))

        volumes.current.value = listOf(stick.copy(state = State.EJECTING, totalBytes = null))
        assertThat(state).isInstanceOf(EjectState.Waiting::class.java)

        volumes.current.value = listOf(stick.copy(state = State.UNMOUNTED, totalBytes = null))
        assertThat(state).isEqualTo(EjectState.SafeToRemove("SONORA"))

        viewModel.dismiss()
        assertThat(state).isEqualTo(EjectState.Idle)
    }
}
