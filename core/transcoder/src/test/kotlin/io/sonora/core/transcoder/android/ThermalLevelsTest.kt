package io.sonora.core.transcoder.android

import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.ThermalLevel
import org.junit.Test

class ThermalLevelsTest {

    @Test
    fun `platform thermal statuses map to levels in order`() {
        assertThat(thermalLevelOf(PowerManager.THERMAL_STATUS_NONE)).isEqualTo(ThermalLevel.NONE)
        assertThat(thermalLevelOf(PowerManager.THERMAL_STATUS_LIGHT)).isEqualTo(ThermalLevel.LIGHT)
        assertThat(thermalLevelOf(PowerManager.THERMAL_STATUS_MODERATE)).isEqualTo(ThermalLevel.MODERATE)
        assertThat(thermalLevelOf(PowerManager.THERMAL_STATUS_SEVERE)).isEqualTo(ThermalLevel.SEVERE)
        assertThat(thermalLevelOf(PowerManager.THERMAL_STATUS_CRITICAL)).isEqualTo(ThermalLevel.CRITICAL)
        assertThat(thermalLevelOf(PowerManager.THERMAL_STATUS_EMERGENCY)).isEqualTo(ThermalLevel.EMERGENCY)
        assertThat(thermalLevelOf(PowerManager.THERMAL_STATUS_SHUTDOWN)).isEqualTo(ThermalLevel.SHUTDOWN)
    }

    @Test
    fun `unknown statuses are treated as hot when above the scale and cool when below`() {
        assertThat(thermalLevelOf(42)).isEqualTo(ThermalLevel.SHUTDOWN)
        assertThat(thermalLevelOf(-1)).isEqualTo(ThermalLevel.NONE)
    }
}
