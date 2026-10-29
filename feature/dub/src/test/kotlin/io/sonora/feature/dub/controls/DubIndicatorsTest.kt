package io.sonora.feature.dub.controls

import com.google.common.truth.Truth.assertThat
import kotlin.math.log10
import org.junit.Test

class DubIndicatorsTest {

    @Test
    fun `full scale fills the meter and the floor empties it`() {
        assertThat(DubIndicators.peakFraction(1f)).isEqualTo(1f)
        assertThat(DubIndicators.peakFraction(1.5f)).isEqualTo(1f)
        assertThat(DubIndicators.peakFraction(0f)).isEqualTo(0f)
        assertThat(DubIndicators.peakFraction(0.001f)).isEqualTo(0f)
    }

    @Test
    fun `the meter is linear in decibels between the floor and full scale`() {
        val expected = ((20 * log10(0.5) - DubIndicators.METER_FLOOR_DB) / -DubIndicators.METER_FLOOR_DB).toFloat()
        assertThat(DubIndicators.peakFraction(0.5f)).isWithin(1e-5f).of(expected)
    }

    @Test
    fun `unusable peaks read as silence`() {
        assertThat(DubIndicators.peakFraction(Float.NaN)).isEqualTo(0f)
        assertThat(DubIndicators.peakFraction(-0.5f)).isEqualTo(0f)
        assertThat(DubIndicators.peakFraction(Float.POSITIVE_INFINITY)).isEqualTo(0f)
    }

    @Test
    fun `the limiter LED lights only above 1 dB of gain reduction`() {
        assertThat(DubIndicators.limiterLit(0f)).isFalse()
        assertThat(DubIndicators.limiterLit(1f)).isFalse()
        assertThat(DubIndicators.limiterLit(1.01f)).isTrue()
        assertThat(DubIndicators.limiterLit(Float.NaN)).isFalse()
    }

    @Test
    fun `the beat LEDs follow the beat in the bar`() {
        assertThat(DubIndicators.beatLed(0.0)).isEqualTo(0)
        assertThat(DubIndicators.beatLed(1.99)).isEqualTo(1)
        assertThat(DubIndicators.beatLed(3.5)).isEqualTo(3)
        assertThat(DubIndicators.beatLed(4.0)).isEqualTo(0)
        assertThat(DubIndicators.beatLed(-0.5)).isEqualTo(3)
        assertThat(DubIndicators.beatLed(null)).isNull()
        assertThat(DubIndicators.beatLed(Double.NaN)).isNull()
    }

    @Test
    fun `every bypass reason has its own message`() {
        val messages = DubBypassReason.entries.map { it.message }
        assertThat(messages).containsNoDuplicates()
        assertThat(DubBypassReason.entries).hasSize(4)
    }
}
