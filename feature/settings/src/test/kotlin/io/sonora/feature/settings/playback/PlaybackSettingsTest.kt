package io.sonora.feature.settings.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.Crossfade
import io.sonora.core.domain.playback.CrossfadeRepository
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainRepository
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.feature.settings.XmlStrings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Playback section of Settings: its copy and its view model. */
@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class PlaybackSettingsTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")
    private val eu = XmlStrings.of("-eu")

    /** In-memory store; [read] holds the first emission back, every save fails with [failure] when set. */
    private class FakeReplayGain : ReplayGainRepository {
        val stored = MutableStateFlow<ReplayGainSettings?>(null)
        var failure: Exception? = null
        override val settings: Flow<ReplayGainSettings> = stored.filterNotNull()
        override suspend fun update(transform: (ReplayGainSettings) -> ReplayGainSettings) {
            failure?.let { throw it }
            stored.value = transform(stored.value ?: ReplayGainSettings())
        }
    }

    /** In-memory crossfade store; every save fails with [failure] when set. */
    private class FakeCrossfade : CrossfadeRepository {
        val stored = MutableStateFlow(Crossfade.DEFAULT_SECONDS)
        var failure: Exception? = null
        override val seconds: Flow<Int> = stored
        override suspend fun setSeconds(seconds: Int) {
            failure?.let { throw it }
            stored.value = Crossfade.clampSeconds(seconds)
        }
    }

    private val store = FakeReplayGain()
    private val crossfade = FakeCrossfade()
    private lateinit var viewModel: PlaybackSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = PlaybackSettingsViewModel(store, crossfade)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // Copy.

    @Test
    fun `the pre-amp reads in decibels with its sign, in the current language`() {
        assertThat(PlaybackSettingsText.preamp(0f, en)).isEqualTo("0 dB")
        assertThat(PlaybackSettingsText.preamp(1.5f, en)).isEqualTo("+1.5 dB")
        assertThat(PlaybackSettingsText.preamp(-6f, en)).isEqualTo("-6 dB")
        assertThat(PlaybackSettingsText.preamp(2.5f, es)).isEqualTo("+2,5 dB")
    }

    @Test
    fun `every mode has a title and a subtitle in every language`() {
        for (strings in listOf(en, es, eu)) {
            val titles = ReplayGainMode.entries.map { PlaybackSettingsText.modeTitle(it, strings) }
            assertThat(titles.toSet()).hasSize(ReplayGainMode.entries.size)
            for (mode in ReplayGainMode.entries) assertThat(PlaybackSettingsText.modeSubtitle(mode, strings)).isNotEmpty()
        }
        assertThat(PlaybackSettingsText.modeTitle(ReplayGainMode.AUTO, en)).isEqualTo("Automatic")
        assertThat(PlaybackSettingsText.modeTitle(ReplayGainMode.TRACK, es)).isEqualTo("Por canción")
    }

    // View model.

    @Test
    fun `the settings are null until the stored ones are read`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.settings.collect {} }
        assertThat(viewModel.settings.value).isNull()

        store.stored.value = ReplayGainSettings(ReplayGainMode.ALBUM, 2f)

        assertThat(viewModel.settings.value).isEqualTo(ReplayGainSettings(ReplayGainMode.ALBUM, 2f))
    }

    @Test
    fun `a chosen mode is stored and the pre-amp kept`() {
        store.stored.value = ReplayGainSettings(ReplayGainMode.OFF, -1f)

        viewModel.setMode(ReplayGainMode.AUTO)

        assertThat(store.stored.value).isEqualTo(ReplayGainSettings(ReplayGainMode.AUTO, -1f))
    }

    @Test
    fun `the pre-amp is stored in half-decibel steps within its range`() {
        viewModel.setPreamp(1.26f)
        assertThat(store.stored.value?.preampDb).isEqualTo(1.5f)

        viewModel.setPreamp(-1.24f)
        assertThat(store.stored.value?.preampDb).isEqualTo(-1f)

        viewModel.setPreamp(8f)
        assertThat(store.stored.value?.preampDb).isEqualTo(6f)
    }

    @Test
    fun `a failed save is reported and can be dismissed`() {
        store.failure = IOException("disk full")

        viewModel.setMode(ReplayGainMode.TRACK)

        assertThat(viewModel.saveFailed.value).isTrue()
        viewModel.dismissSaveFailed()
        assertThat(viewModel.saveFailed.value).isFalse()
    }

    // Crossfade.

    @Test
    fun `the crossfade slider shows nothing until the stored length is read, then that length`() = runTest {
        assertThat(viewModel.crossfadeSeconds.value).isNull()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.crossfadeSeconds.collect {} }

        assertThat(viewModel.crossfadeSeconds.value).isEqualTo(0)
        crossfade.stored.value = 8
        assertThat(viewModel.crossfadeSeconds.value).isEqualTo(8)
    }

    @Test
    fun `a chosen crossfade length is stored`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.crossfadeSeconds.collect {} }

        viewModel.setCrossfadeSeconds(5)

        assertThat(crossfade.stored.value).isEqualTo(5)
        assertThat(viewModel.crossfadeSeconds.value).isEqualTo(5)
    }

    @Test
    fun `a failed crossfade save keeps the stored length on screen`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.crossfadeSeconds.collect {} }
        crossfade.stored.value = 3
        crossfade.failure = IOException("disk full")

        viewModel.setCrossfadeSeconds(10)

        assertThat(viewModel.crossfadeSeconds.value).isEqualTo(3)
    }

    @Test
    fun `the crossfade length reads Off at zero and in seconds otherwise, in every language`() {
        assertThat(CrossfadeText.label(0, en)).isEqualTo("Off")
        assertThat(CrossfadeText.label(6, en)).isEqualTo("6 s")
        assertThat(CrossfadeText.label(0, es)).isEqualTo("Desactivado")
        assertThat(CrossfadeText.label(12, es)).isEqualTo("12 s")
        assertThat(CrossfadeText.label(0, eu)).isEqualTo("Desaktibatuta")
    }
}
