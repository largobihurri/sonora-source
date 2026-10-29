package io.sonora.core.domain.equalizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EqualizerSettingsTest {

    @Test
    fun `a fresh install has the equalizer off, in four bands, flat`() {
        val settings = EqualizerSettings()

        assertThat(settings.enabled).isFalse()
        assertThat(settings.mode).isEqualTo(EqualizerMode.FOUR_BANDS)
        for (mode in EqualizerMode.entries) assertThat(settings.gainsFor(mode)).isEqualTo(List(mode.bandCount) { 0f })
        assertThat(settings.isFlat).isTrue()
        assertThat(settings.automaticPreamp).isTrue()
    }

    @Test
    fun `the automatic preamp switch survives every other change and tells settings apart`() {
        val manual = EqualizerSettings().withAutomaticPreamp(false)
        val changed = manual
            .withEnabled(true)
            .withMode(EqualizerMode.ELEVEN_BANDS)
            .withGain(EqualizerMode.ELEVEN_BANDS, 2, 3f)
            .reset(EqualizerMode.FOUR_BANDS)

        assertThat(manual.automaticPreamp).isFalse()
        assertThat(changed.automaticPreamp).isFalse()
        assertThat(manual).isNotEqualTo(EqualizerSettings())
        assertThat(manual.withAutomaticPreamp(true)).isEqualTo(EqualizerSettings())
        assertThat(manual.withAutomaticPreamp(true).hashCode()).isEqualTo(EqualizerSettings().hashCode())
    }

    @Test
    fun `each mode keeps its own gains`() {
        val settings = EqualizerSettings()
            .withGain(EqualizerMode.FOUR_BANDS, 1, 3f)
            .withGain(EqualizerMode.TWENTY_BANDS, 19, -4f)
            .withMode(EqualizerMode.TWENTY_BANDS)

        assertThat(settings.activeGains[19]).isEqualTo(-4f)
        assertThat(settings.gainsFor(EqualizerMode.FOUR_BANDS)).containsExactly(0f, 3f, 0f, 0f).inOrder()
        assertThat(settings.withMode(EqualizerMode.FOUR_BANDS).activeGains).containsExactly(0f, 3f, 0f, 0f).inOrder()
    }

    @Test
    fun `gains are clamped to plus or minus 12 dB in tenths of a decibel`() {
        val settings = EqualizerSettings()
            .withGain(EqualizerMode.FOUR_BANDS, 0, 20f)
            .withGain(EqualizerMode.FOUR_BANDS, 1, -13f)
            .withGain(EqualizerMode.FOUR_BANDS, 2, 2.34f)
            .withGain(EqualizerMode.FOUR_BANDS, 3, -0.04f)

        assertThat(settings.activeGains).containsExactly(12f, -12f, 2.3f, 0f).inOrder()
    }

    @Test
    fun `reset flattens only the given mode`() {
        val settings = EqualizerSettings(enabled = true)
            .withGain(EqualizerMode.FOUR_BANDS, 0, 5f)
            .withGain(EqualizerMode.ELEVEN_BANDS, 4, 5f)
            .reset(EqualizerMode.FOUR_BANDS)

        assertThat(settings.gainsFor(EqualizerMode.FOUR_BANDS)).isEqualTo(List(4) { 0f })
        assertThat(settings.gainsFor(EqualizerMode.ELEVEN_BANDS)[4]).isEqualTo(5f)
        assertThat(settings.enabled).isTrue()
    }

    @Test
    fun `stored gains of the wrong length are padded or cut to the mode's bands`() {
        val settings = EqualizerSettings(
            gains = mapOf(EqualizerMode.FOUR_BANDS to listOf(1f, 2f), EqualizerMode.ELEVEN_BANDS to List(30) { 1f }),
        )

        assertThat(settings.gainsFor(EqualizerMode.FOUR_BANDS)).containsExactly(1f, 2f, 0f, 0f).inOrder()
        assertThat(settings.gainsFor(EqualizerMode.ELEVEN_BANDS)).hasSize(11)
    }

    @Test
    fun `an out of range band is ignored`() {
        val settings = EqualizerSettings().withGain(EqualizerMode.FOUR_BANDS, 7, 3f)

        assertThat(settings).isEqualTo(EqualizerSettings())
    }
}
