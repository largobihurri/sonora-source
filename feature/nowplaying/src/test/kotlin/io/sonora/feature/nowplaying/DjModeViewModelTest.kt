package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.conversion.PerformanceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class DjModeViewModelTest {

    private class FakeSettings(on: Boolean) : DjModeSettings {
        private val state = MutableStateFlow(on)
        override val enabled: StateFlow<Boolean> = state

        override fun setEnabled(enabled: Boolean) {
            state.value = enabled
        }

        private val mode = MutableStateFlow(PerformanceMode.DEFAULT)
        override val performance: StateFlow<PerformanceMode> = mode

        override fun setPerformance(performance: PerformanceMode) {
            mode.value = performance
        }
    }

    /** Reading [status] is what starts the analysis in the app, so the fake counts how often it is read. */
    private class FakeStatus : DjAnalysisStatusSource {
        val flow = MutableStateFlow<Map<String, AnalysisStatus>>(emptyMap())
        var reads = 0
            private set

        override val status: StateFlow<Map<String, AnalysisStatus>>
            get() {
                reads++
                return flow
            }
    }

    private val source = FakeStatus()
    private val playingUri = MutableStateFlow<String?>(null)
    private val analysing = AnalysisStatus.Analysing(AnalysisStage.entries.first(), 0.4f)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(settings: DjModeSettings = FakeSettings(on = true)) = DjModeViewModel(settings, source, playingUri)

    @Test
    fun `the view model shows whether DJ mode is on`() {
        assertThat(viewModel(FakeSettings(on = false)).enabled.value).isFalse()
        assertThat(viewModel(FakeSettings(on = true)).enabled.value).isTrue()
    }

    @Test
    fun `setting it stores it`() {
        val settings = FakeSettings(on = false)
        val viewModel = viewModel(settings)

        viewModel.setEnabled(true)

        assertThat(settings.enabled.value).isTrue()
        assertThat(viewModel.enabled.value).isTrue()
    }

    @Test
    fun `the status follows the playing track`() {
        source.flow.value = mapOf("content://a" to AnalysisStatus.Analysed, "content://b" to analysing)
        playingUri.value = "content://a"
        val viewModel = viewModel()

        assertThat(viewModel.playingStatus.value).isEqualTo(AnalysisStatus.Analysed)

        source.flow.value = mapOf("content://a" to AnalysisStatus.Failed("decode"), "content://b" to analysing)
        assertThat(viewModel.playingStatus.value).isEqualTo(AnalysisStatus.Failed("decode"))
    }

    @Test
    fun `a track change switches the status`() {
        source.flow.value = mapOf("content://a" to AnalysisStatus.Analysed, "content://b" to analysing)
        playingUri.value = "content://a"
        val viewModel = viewModel()

        playingUri.value = "content://b"

        assertThat(viewModel.playingStatus.value).isEqualTo(analysing)
    }

    @Test
    fun `a track the analysis has not reported yet has no status`() {
        playingUri.value = "content://a"
        val viewModel = viewModel()
        assertThat(viewModel.playingStatus.value).isNull()

        source.flow.value = mapOf("content://a" to AnalysisStatus.Queued)
        assertThat(viewModel.playingStatus.value).isEqualTo(AnalysisStatus.Queued)

        playingUri.value = null
        assertThat(viewModel.playingStatus.value).isNull()
    }

    @Test
    fun `turning DJ mode off clears the status and on brings it back`() {
        source.flow.value = mapOf("content://a" to AnalysisStatus.Analysed)
        playingUri.value = "content://a"
        val settings = FakeSettings(on = true)
        val viewModel = viewModel(settings)
        assertThat(viewModel.playingStatus.value).isEqualTo(AnalysisStatus.Analysed)

        viewModel.setEnabled(false)
        assertThat(viewModel.playingStatus.value).isNull()

        viewModel.setEnabled(true)
        assertThat(viewModel.playingStatus.value).isEqualTo(AnalysisStatus.Analysed)
    }

    @Test
    fun `with DJ mode off the analysis status is never read`() {
        playingUri.value = "content://a"

        viewModel(FakeSettings(on = false))

        assertThat(source.reads).isEqualTo(0)
    }
}
