package io.sonora.core.djanalysis

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.ThermalLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DjModeSettingsTest {

    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences(SharedPreferencesDjModeSettings.FILE_NAME, Context.MODE_PRIVATE)

    @Test
    fun `DJ mode is off until it is turned on`() {
        assertThat(SharedPreferencesDjModeSettings(preferences).enabled.value).isFalse()
    }

    @Test
    fun `turning it on is seen at once and kept for the next process`() {
        val settings = SharedPreferencesDjModeSettings(preferences)

        settings.setEnabled(true)

        assertThat(settings.enabled.value).isTrue()
        assertThat(SharedPreferencesDjModeSettings(preferences).enabled.value).isTrue()
    }

    @Test
    fun `turning it off again`() {
        val settings = SharedPreferencesDjModeSettings(preferences)
        settings.setEnabled(true)

        settings.setEnabled(false)

        assertThat(settings.enabled.value).isFalse()
        assertThat(SharedPreferencesDjModeSettings(preferences).enabled.value).isFalse()
    }

    @Test
    fun `turning DJ mode on turns the DUB layout off`() {
        var dubOn = true
        val settings = SharedPreferencesDjModeSettings(preferences) { dubOn = false }

        settings.setEnabled(true)

        assertThat(dubOn).isFalse()
        assertThat(settings.enabled.value).isTrue()
        assertThat(SharedPreferencesDjModeSettings(preferences).enabled.value).isTrue()
    }

    @Test
    fun `a switch from the DUB layout to DJ never reads both layouts off`() = runTest {
        // An immediate collector sees every intermediate value, as the deck's loop does on the main thread: a moment
        // with both layouts off would let a held loop go.
        val dub = MutableStateFlow(true)
        val settings = SharedPreferencesDjModeSettings(preferences) { dub.value = false }
        val deckOn = ArrayList<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(settings.enabled, dub) { djOn, dubOn -> djOn || dubOn }.collect { deckOn += it }
        }

        settings.setEnabled(true)

        assertThat(deckOn).doesNotContain(false)
        assertThat(dub.value).isFalse()
        assertThat(settings.enabled.value).isTrue()
    }

    @Test
    fun `turning DJ mode off or choosing a performance leaves the DUB layout as it is`() {
        var calls = 0
        val settings = SharedPreferencesDjModeSettings(preferences) { calls++ }
        settings.setEnabled(true)
        calls = 0

        settings.setEnabled(false)
        settings.setPerformance(PerformanceMode.LOW_RESOURCES)

        assertThat(calls).isEqualTo(0)
        assertThat(SharedPreferencesDjModeSettings(preferences).enabled.value).isFalse()
    }

    @Test
    fun `battery saver or a severe thermal state pauses background work`() {
        // PowerManager.THERMAL_STATUS_NONE .. SHUTDOWN are 0 .. 6; SEVERE is 3.
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 0)).isEqualTo(DeviceLoad())
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 2)).isEqualTo(DeviceLoad(thermalLevel = ThermalLevel.MODERATE))
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 3)).isEqualTo(DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SEVERE))
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 6)).isEqualTo(DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SHUTDOWN))
        assertThat(DeviceLoads.of(powerSaveMode = true, thermalStatus = 0)).isEqualTo(DeviceLoad(batterySaver = true))
    }

    @Test
    fun `the analysis performance is normal until it is chosen`() {
        assertThat(SharedPreferencesDjModeSettings(preferences).performance.value).isEqualTo(PerformanceMode.NORMAL)
    }

    @Test
    fun `a chosen performance is seen at once and kept for the next process`() {
        val settings = SharedPreferencesDjModeSettings(preferences)

        settings.setPerformance(PerformanceMode.LOW_RESOURCES)

        assertThat(settings.performance.value).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(SharedPreferencesDjModeSettings(preferences).performance.value).isEqualTo(PerformanceMode.LOW_RESOURCES)
        settings.setPerformance(PerformanceMode.HIGH_PERFORMANCE)
        assertThat(SharedPreferencesDjModeSettings(preferences).performance.value).isEqualTo(PerformanceMode.HIGH_PERFORMANCE)
    }

    @Test
    fun `the performance lives in the DJ mode preferences next to the flag and does not touch it`() {
        val settings = SharedPreferencesDjModeSettings(preferences)
        settings.setEnabled(true)

        settings.setPerformance(PerformanceMode.HIGH_PERFORMANCE)

        assertThat(SharedPreferencesDjModeSettings.PERFORMANCE_KEY).isEqualTo("dj_analysis_performance")
        assertThat(preferences.getString(SharedPreferencesDjModeSettings.PERFORMANCE_KEY, null)).isEqualTo("HIGH_PERFORMANCE")
        assertThat(preferences.getBoolean(SharedPreferencesDjModeSettings.KEY, false)).isTrue()
        assertThat(preferences.all.keys).containsExactly(SharedPreferencesDjModeSettings.KEY, SharedPreferencesDjModeSettings.PERFORMANCE_KEY)
    }

    @Test
    fun `an unreadable stored performance falls back to normal`() {
        preferences.edit().putString(SharedPreferencesDjModeSettings.PERFORMANCE_KEY, "TURBO").commit()

        assertThat(SharedPreferencesDjModeSettings(preferences).performance.value).isEqualTo(PerformanceMode.NORMAL)
    }

    @Test
    fun `the thermal status maps to the level the budget steps down on`() {
        // PowerManager.THERMAL_STATUS_NONE .. SHUTDOWN are 0 .. 6.
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 0).thermalLevel).isEqualTo(ThermalLevel.NONE)
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 1).thermalLevel).isEqualTo(ThermalLevel.LIGHT)
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 2).thermalLevel).isEqualTo(ThermalLevel.MODERATE)
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 3).thermalLevel).isEqualTo(ThermalLevel.SEVERE)
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 6).thermalLevel).isEqualTo(ThermalLevel.SHUTDOWN)
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = 9).thermalLevel).isEqualTo(ThermalLevel.SHUTDOWN)
        assertThat(DeviceLoads.of(powerSaveMode = false, thermalStatus = -1).thermalLevel).isEqualTo(ThermalLevel.NONE)
    }
}
