package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class DubTextTest {

    private val english = Locale.ENGLISH
    private val spanish = Locale.forLanguageTag("es-ES")
    private val nbsp = ' '

    @Test
    fun `frequencies below 1 kHz are whole hertz`() {
        assertThat(DubText.frequency(80f, english)).isEqualTo("80${nbsp}Hz")
        assertThat(DubText.frequency(249.6f, english)).isEqualTo("250${nbsp}Hz")
        assertThat(DubText.frequency(20f, english)).isEqualTo("20${nbsp}Hz")
    }

    @Test
    fun `frequencies from 1 kHz up are kilohertz with at most one decimal`() {
        assertThat(DubText.frequency(1_200f, english)).isEqualTo("1.2${nbsp}kHz")
        assertThat(DubText.frequency(5_000f, english)).isEqualTo("5${nbsp}kHz")
        assertThat(DubText.frequency(20_000f, english)).isEqualTo("20${nbsp}kHz")
        assertThat(DubText.frequency(12_540f, english)).isEqualTo("12.5${nbsp}kHz")
        assertThat(DubText.frequency(999.7f, english)).isEqualTo("1${nbsp}kHz")
    }

    @Test
    fun `frequencies follow the locale's decimal separator`() {
        assertThat(DubText.frequency(1_200f, spanish)).isEqualTo("1,2${nbsp}kHz")
    }

    @Test
    fun `unusable frequencies read as zero hertz`() {
        assertThat(DubText.frequency(Float.NaN, english)).isEqualTo("0${nbsp}Hz")
        assertThat(DubText.frequency(-5f, english)).isEqualTo("0${nbsp}Hz")
    }

    @Test
    fun `levels are signed decibels with at most one decimal`() {
        assertThat(DubText.decibels(0.0, english)).isEqualTo("0${nbsp}dB")
        assertThat(DubText.decibels(-0.01, english)).isEqualTo("0${nbsp}dB")
        assertThat(DubText.decibels(6.0, english)).isEqualTo("+6${nbsp}dB")
        assertThat(DubText.decibels(-12.0, english)).isEqualTo("-12${nbsp}dB")
        assertThat(DubText.decibels(-3.46, english)).isEqualTo("-3.5${nbsp}dB")
        assertThat(DubText.decibels(-3.46, spanish)).isEqualTo("-3,5${nbsp}dB")
    }

    @Test
    fun `the bottom of a fader reads minus infinity`() {
        assertThat(DubText.decibels(Double.NEGATIVE_INFINITY, english)).isEqualTo("-∞${nbsp}dB")
        assertThat(DubText.decibels(Double.NaN, english)).isEqualTo("-∞${nbsp}dB")
    }

    @Test
    fun `resonance shows one decimal`() {
        assertThat(DubText.q(0.7f, english)).isEqualTo("0.7")
        assertThat(DubText.q(2.366f, spanish)).isEqualTo("2,4")
        assertThat(DubText.q(8f, english)).isEqualTo("8")
    }
}
