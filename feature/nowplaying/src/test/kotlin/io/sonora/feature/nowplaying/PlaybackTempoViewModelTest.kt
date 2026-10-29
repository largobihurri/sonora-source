package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.PlaybackTempoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class PlaybackTempoViewModelTest {

    private class FakeTempo : PlaybackTempoRepository {
        override val tempo = MutableStateFlow(PlaybackTempo.DEFAULT)

        /** What was previewed, in order. */
        val previews = mutableListOf<PlaybackTempo?>()

        override fun preview(tempo: PlaybackTempo?) {
            previews += tempo
        }

        override suspend fun update(transform: (PlaybackTempo) -> PlaybackTempo) {
            tempo.value = transform(tempo.value)
        }
    }

    private val store = FakeTempo()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the view model shows the stored tempo`() {
        store.tempo.value = PlaybackTempo.ofPercent(125, 2)

        val viewModel = PlaybackTempoViewModel(store)

        assertThat(viewModel.tempo.value).isEqualTo(PlaybackTempo.ofPercent(125, 2))
    }

    @Test
    fun `sliders and step buttons store snapped, clamped values`() {
        val viewModel = PlaybackTempoViewModel(store)

        viewModel.setSpeed(1.33f)
        assertThat(store.tempo.value.speedPercent).isEqualTo(135)
        viewModel.stepSpeed(-1)
        assertThat(store.tempo.value.speedPercent).isEqualTo(130)
        viewModel.setSemitones(4.6f)
        assertThat(store.tempo.value.semitones).isEqualTo(5)
        viewModel.stepPitch(20)
        assertThat(store.tempo.value.semitones).isEqualTo(12)
        assertThat(viewModel.tempo.value).isEqualTo(PlaybackTempo.ofPercent(130, 12))
    }

    @Test
    fun `reset goes back to normal speed and pitch`() {
        store.tempo.value = PlaybackTempo.ofPercent(50, -7)
        val viewModel = PlaybackTempoViewModel(store)

        viewModel.reset()

        assertThat(store.tempo.value).isEqualTo(PlaybackTempo.DEFAULT)
    }

    @Test
    fun `the panel opens and closes`() {
        val viewModel = PlaybackTempoViewModel(store)
        assertThat(viewModel.panelOpen.value).isFalse()

        viewModel.openPanel()
        assertThat(viewModel.panelOpen.value).isTrue()
        viewModel.closePanel()
        assertThat(viewModel.panelOpen.value).isFalse()
    }
}
