package io.sonora.core.djanalysis

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DjQuantizeSettingsTest {

    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences(SharedPreferencesDjModeSettings.FILE_NAME, Context.MODE_PRIVATE)

    @Test
    fun `quantize is on until it is turned off, like a CDJ`() {
        assertThat(SharedPreferencesDjQuantizeSettings(preferences).quantize.value).isTrue()
    }

    @Test
    fun `turning it off is seen at once and kept for the next process`() {
        val settings = SharedPreferencesDjQuantizeSettings(preferences)

        settings.setQuantize(false)

        assertThat(settings.quantize.value).isFalse()
        assertThat(SharedPreferencesDjQuantizeSettings(preferences).quantize.value).isFalse()
    }

    @Test
    fun `turning it back on`() {
        val settings = SharedPreferencesDjQuantizeSettings(preferences)
        settings.setQuantize(false)

        settings.setQuantize(true)

        assertThat(settings.quantize.value).isTrue()
        assertThat(SharedPreferencesDjQuantizeSettings(preferences).quantize.value).isTrue()
    }

    @Test
    fun `it shares the DJ mode preference file without touching the DJ mode flag`() {
        SharedPreferencesDjModeSettings(preferences).setEnabled(true)

        SharedPreferencesDjQuantizeSettings(preferences).setQuantize(false)

        assertThat(SharedPreferencesDjModeSettings(preferences).enabled.value).isTrue()
    }
}
