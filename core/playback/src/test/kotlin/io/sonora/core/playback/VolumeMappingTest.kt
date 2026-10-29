package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeMappingTest {

    @Test
    fun `step maps to fraction across range`() {
        assertThat(VolumeMapping.stepToFraction(0, 0, 15)).isEqualTo(0f)
        assertThat(VolumeMapping.stepToFraction(15, 0, 15)).isEqualTo(1f)
        assertThat(VolumeMapping.stepToFraction(3, 1, 5)).isEqualTo(0.5f)
    }

    @Test
    fun `fraction quantizes to nearest step`() {
        assertThat(VolumeMapping.fractionToStep(0.5f, 0, 15)).isEqualTo(8)
        assertThat(VolumeMapping.fractionToStep(0.03f, 0, 15)).isEqualTo(0)
        assertThat(VolumeMapping.fractionToStep(0.04f, 0, 15)).isEqualTo(1)
        assertThat(VolumeMapping.fractionToStep(1f, 1, 25)).isEqualTo(25)
    }

    @Test
    fun `out of range and degenerate inputs are clamped`() {
        assertThat(VolumeMapping.fractionToStep(-1f, 0, 15)).isEqualTo(0)
        assertThat(VolumeMapping.fractionToStep(2f, 0, 15)).isEqualTo(15)
        assertThat(VolumeMapping.fractionToStep(Float.NaN, 0, 15)).isEqualTo(0)
        assertThat(VolumeMapping.fractionToStep(0.7f, 5, 5)).isEqualTo(5)
        assertThat(VolumeMapping.stepToFraction(5, 5, 5)).isEqualTo(0f)
    }

    @Test
    fun `round trip preserves every step`() {
        for (step in 0..15) {
            val f = VolumeMapping.stepToFraction(step, 0, 15)
            assertThat(VolumeMapping.fractionToStep(f, 0, 15)).isEqualTo(step)
        }
    }
}
