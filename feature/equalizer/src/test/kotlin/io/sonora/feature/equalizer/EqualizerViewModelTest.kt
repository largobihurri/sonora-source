package io.sonora.feature.equalizer

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.port.EqualizerRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class EqualizerViewModelTest {

    /**
     * In memory: [stored] is what reached the store, [live] what readers see (a preview included). While [failing],
     * an update throws like a full disk and readers go back to what is stored, as the real store does.
     */
    private class FakeRepository(initial: EqualizerSettings = EqualizerSettings()) : EqualizerRepository {
        var stored = initial
        val live = MutableStateFlow(initial)
        var failing = false
        override val settings: Flow<EqualizerSettings> = live

        override suspend fun update(transform: (EqualizerSettings) -> EqualizerSettings) {
            if (failing) {
                live.value = stored
                throw IOException("disk full")
            }
            stored = transform(live.value)
            live.value = stored
        }

        override fun preview(settings: EqualizerSettings) {
            live.value = settings
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun runWithViewModel(repository: FakeRepository, block: suspend (EqualizerViewModel) -> Unit) = runTest(dispatcher) {
        val viewModel = EqualizerViewModel(repository)
        val collecting = launch { viewModel.settings.collect {} }
        block(viewModel)
        collecting.cancel()
    }

    @Test
    fun `the screen shows the stored settings`() {
        val repository = FakeRepository(EqualizerSettings(enabled = true, mode = EqualizerMode.ELEVEN_BANDS))

        runWithViewModel(repository) { viewModel ->
            assertThat(viewModel.settings.value.enabled).isTrue()
            assertThat(viewModel.settings.value.mode).isEqualTo(EqualizerMode.ELEVEN_BANDS)
        }
    }

    @Test
    fun `the switch and the mode are stored`() {
        val repository = FakeRepository()

        runWithViewModel(repository) { viewModel ->
            viewModel.setEnabled(true)
            viewModel.selectMode(EqualizerMode.TWENTY_BANDS)
        }

        assertThat(repository.stored.enabled).isTrue()
        assertThat(repository.stored.mode).isEqualTo(EqualizerMode.TWENTY_BANDS)
    }

    @Test
    fun `the automatic preamp switch is stored`() {
        val repository = FakeRepository(EqualizerSettings(enabled = true))

        runWithViewModel(repository) { viewModel ->
            viewModel.setAutomaticPreamp(false)
            assertThat(viewModel.settings.value.automaticPreamp).isFalse()
        }

        assertThat(repository.stored.automaticPreamp).isFalse()
        assertThat(repository.stored.enabled).isTrue()
    }

    @Test
    fun `a dragged band is heard at once but stored only on release`() {
        val repository = FakeRepository(EqualizerSettings(enabled = true))

        runWithViewModel(repository) { viewModel ->
            viewModel.dragBand(1, 4.2f)
            assertThat(repository.live.value.activeGains[1]).isEqualTo(4.2f)
            assertThat(repository.stored.activeGains[1]).isEqualTo(0f)
            assertThat(viewModel.settings.value.activeGains[1]).isEqualTo(4.2f)

            viewModel.dragBand(1, 5.5f)
            viewModel.releaseBand(1, 5.5f)
        }

        assertThat(repository.stored.activeGains).containsExactly(0f, 5.5f, 0f, 0f).inOrder()
    }

    @Test
    fun `a cancelled drag puts the band back`() {
        val repository = FakeRepository(EqualizerSettings(enabled = true).withGain(EqualizerMode.FOUR_BANDS, 2, -3f))

        runWithViewModel(repository) { viewModel ->
            viewModel.dragBand(2, 8f)
            viewModel.cancelBand(2, -3f)
        }

        assertThat(repository.live.value.activeGains[2]).isEqualTo(-3f)
        assertThat(repository.stored.activeGains[2]).isEqualTo(-3f)
    }

    @Test
    fun `a double tap sets one band back to 0 dB`() {
        val repository = FakeRepository(
            EqualizerSettings(enabled = true).withGain(EqualizerMode.FOUR_BANDS, 0, 6f).withGain(EqualizerMode.FOUR_BANDS, 3, 2f),
        )

        runWithViewModel(repository) { viewModel -> viewModel.resetBand(0) }

        assertThat(repository.stored.activeGains).containsExactly(0f, 0f, 0f, 2f).inOrder()
    }

    @Test
    fun `reset flattens only the mode on screen`() {
        val repository = FakeRepository(
            EqualizerSettings(enabled = true, mode = EqualizerMode.ELEVEN_BANDS)
                .withGain(EqualizerMode.ELEVEN_BANDS, 5, 6f)
                .withGain(EqualizerMode.FOUR_BANDS, 1, 3f),
        )

        runWithViewModel(repository) { viewModel -> viewModel.reset() }

        assertThat(repository.stored.gainsFor(EqualizerMode.ELEVEN_BANDS)).isEqualTo(List(11) { 0f })
        assertThat(repository.stored.gainsFor(EqualizerMode.FOUR_BANDS)[1]).isEqualTo(3f)
        assertThat(repository.stored.enabled).isTrue()
    }

    @Test
    fun `a change that cannot be stored is dropped without crashing, and the screen keeps what is stored`() {
        val repository = FakeRepository(EqualizerSettings(enabled = true))
        repository.failing = true

        runWithViewModel(repository) { viewModel ->
            viewModel.dragBand(1, 6f)
            viewModel.releaseBand(1, 6f)
            viewModel.setEnabled(false)

            assertThat(viewModel.settings.value).isEqualTo(EqualizerSettings(enabled = true))
        }

        assertThat(repository.stored).isEqualTo(EqualizerSettings(enabled = true))
    }

    @Test
    fun `a second drag before the screen caught up with the first keeps the first`() {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val repository = FakeRepository(EqualizerSettings(enabled = true))

        runTest(main) {
            val viewModel = EqualizerViewModel(repository)
            val collecting = launch { viewModel.settings.collect {} }
            runCurrent()

            // Two fingers, or two quick drags: no frame (and no new settings on screen) between them.
            viewModel.dragBand(1, 4f)
            viewModel.dragBand(2, 3f)

            assertThat(repository.live.value.activeGains).containsExactly(0f, 4f, 3f, 0f).inOrder()
            runCurrent()
            viewModel.dragBand(1, 5f)
            assertThat(repository.live.value.activeGains).containsExactly(0f, 5f, 3f, 0f).inOrder()
            collecting.cancel()
        }
    }
}
