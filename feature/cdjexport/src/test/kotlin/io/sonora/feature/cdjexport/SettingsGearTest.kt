package io.sonora.feature.cdjexport

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Export tab's Settings gear: Settings would cover the screen that keeps it on during a format. */
class SettingsGearTest {

    private val device = DeviceItem("id", "Stick", "0781:5581", isSupported = true)
    private val details = StickDetails("SanDisk", "SanDisk", 16_008_609_792, 512, false)
    private val formatting = PrepareStep.Formatting(device, details, "SONORA")

    @Test
    fun `the gear shows whenever no stick is being formatted`() {
        assertThat(settingsGearShown(PrepareStep.ChooseDevice)).isTrue()
        assertThat(settingsGearShown(PrepareStep.Opening(device))).isTrue()
        assertThat(settingsGearShown(PrepareStep.Finished(device, FormatOutcome.Formatted("SONORA", 16_000_000_000)))).isTrue()
    }

    @Test
    fun `no gear while a stick is being formatted, cancelling included`() {
        assertThat(settingsGearShown(formatting)).isFalse()
        assertThat(settingsGearShown(formatting.copy(cancelling = true))).isFalse()
    }
}
