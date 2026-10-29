package io.sonora.core.data.dub

import android.content.Context
import com.google.common.truth.Truth.assertThat
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
class DubModeSettingsTest {

    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences(SharedPreferencesDubModeSettings.FILE_NAME, Context.MODE_PRIVATE)

    @Test
    fun `DUB mode is off until it is turned on`() {
        assertThat(SharedPreferencesDubModeSettings(preferences).enabled.value).isFalse()
    }

    @Test
    fun `turning it on is seen at once and kept for the next process`() {
        val settings = SharedPreferencesDubModeSettings(preferences)

        settings.setEnabled(true)

        assertThat(settings.enabled.value).isTrue()
        assertThat(SharedPreferencesDubModeSettings(preferences).enabled.value).isTrue()
    }

    @Test
    fun `turning it off again`() {
        val settings = SharedPreferencesDubModeSettings(preferences)
        settings.setEnabled(true)

        settings.setEnabled(false)

        assertThat(settings.enabled.value).isFalse()
        assertThat(SharedPreferencesDubModeSettings(preferences).enabled.value).isFalse()
    }

    @Test
    fun `the flag lives alone in its own preference file under its own key`() {
        SharedPreferencesDubModeSettings(preferences).setEnabled(true)

        assertThat(SharedPreferencesDubModeSettings.FILE_NAME).isEqualTo("sonora_dub_mode")
        assertThat(preferences.all).containsExactly("dub_mode_enabled", true)
    }

    @Test
    fun `turning DUB mode on turns the DJ layout off`() {
        val dj = FakeDjLayout(on = true)
        val settings = SharedPreferencesDubModeSettings(preferences) { dj.on = false }

        settings.setEnabled(true)

        assertThat(dj.on).isFalse()
        assertThat(settings.enabled.value).isTrue()
    }

    @Test
    fun `a switch from the DJ layout to DUB never reads both layouts off`() = runTest {
        // An immediate collector sees every intermediate value, as the deck's loop does on the main thread: a moment
        // with both layouts off would let a held loop go.
        val dj = MutableStateFlow(true)
        val settings = SharedPreferencesDubModeSettings(preferences) { dj.value = false }
        val deckOn = ArrayList<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(dj, settings.enabled) { djOn, dubOn -> djOn || dubOn }.collect { deckOn += it }
        }

        settings.setEnabled(true)

        assertThat(deckOn).doesNotContain(false)
        assertThat(dj.value).isFalse()
        assertThat(settings.enabled.value).isTrue()
    }

    @Test
    fun `turning DUB mode off leaves the DJ layout as it is`() {
        val dj = FakeDjLayout(on = false)
        val settings = SharedPreferencesDubModeSettings(preferences) { dj.on = false; dj.calls++ }
        settings.setEnabled(true)
        dj.on = true
        dj.calls = 0

        settings.setEnabled(false)

        assertThat(dj.on).isTrue()
        assertThat(dj.calls).isEqualTo(0)
    }

    @Test
    fun `both layouts found on at start (a crash between the two writes) leaves DJ on and turns DUB off`() {
        preferences.edit().putBoolean(SharedPreferencesDubModeSettings.KEY, true).commit()
        var turnedOff = 0

        val settings = SharedPreferencesDubModeSettings(preferences, turnOtherLayoutOff = { turnedOff++ }, otherLayoutOn = { true })

        assertThat(settings.enabled.value).isFalse()
        assertThat(preferences.getBoolean(SharedPreferencesDubModeSettings.KEY, true)).isFalse()
        assertThat(turnedOff).isEqualTo(0) // DJ wins: it is left on
    }

    @Test
    fun `DUB mode found on alone at start stays on`() {
        preferences.edit().putBoolean(SharedPreferencesDubModeSettings.KEY, true).commit()

        val settings = SharedPreferencesDubModeSettings(preferences, otherLayoutOn = { false })

        assertThat(settings.enabled.value).isTrue()
        assertThat(preferences.getBoolean(SharedPreferencesDubModeSettings.KEY, false)).isTrue()
    }

    private class FakeDjLayout(var on: Boolean) {
        var calls = 0
    }
}
