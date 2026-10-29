package io.sonora.feature.equalizer

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.equalizer.EqualizerLayout
import io.sonora.core.domain.equalizer.EqualizerMode
import java.util.Locale
import org.junit.Test

class EqualizerTextTest {

    private val english = Locale.ENGLISH
    private val spanish = Locale.forLanguageTag("es-ES")

    @Test
    fun `band frequencies read like a hardware equalizer`() {
        val labels = EqualizerLayout.bands(EqualizerMode.ELEVEN_BANDS).map { EqualizerText.frequency(it.nominalHz, english) }

        assertThat(labels).containsExactly("16", "31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k").inOrder()
    }

    @Test
    fun `fractional kilohertz use the language's decimal mark`() {
        assertThat(EqualizerText.frequency(1_400.0, english)).isEqualTo("1.4k")
        assertThat(EqualizerText.frequency(1_400.0, spanish)).isEqualTo("1,4k")
        assertThat(EqualizerText.frequency(11_000.0, spanish)).isEqualTo("11k")
    }

    @Test
    fun `gains carry their sign and one decimal`() {
        assertThat(EqualizerText.gain(3.5f, english)).isEqualTo("+3.5")
        assertThat(EqualizerText.gain(-12f, english)).isEqualTo("−12.0")
        assertThat(EqualizerText.gain(0f, spanish)).isEqualTo("0,0")
        assertThat(EqualizerText.gain(0.4f, spanish)).isEqualTo("+0,4")
    }

    @Test
    fun `the response is drawn across the audible range on a log scale`() {
        val points = EqualizerText.curveFrequencies(5)

        assertThat(points.first()).isWithin(1e-9).of(20.0)
        assertThat(points.last()).isWithin(1e-6).of(20_000.0)
        assertThat(points[2]).isWithin(1e-6).of(632.455532)
    }
}
