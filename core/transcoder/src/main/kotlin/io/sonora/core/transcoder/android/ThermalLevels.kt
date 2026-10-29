package io.sonora.core.transcoder.android

import android.os.PowerManager
import io.sonora.core.domain.conversion.ThermalLevel

/** Maps `PowerManager.THERMAL_STATUS_*`; statuses beyond the known scale count as the hottest. */
internal fun thermalLevelOf(status: Int): ThermalLevel = when {
    status <= PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NONE
    status >= PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.SHUTDOWN
    else -> when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
        else -> ThermalLevel.EMERGENCY
    }
}
