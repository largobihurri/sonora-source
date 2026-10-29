package io.sonora.core.djanalysis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.domain.conversion.ThermalLevel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** The device conditions that pause background analysis, as they change. */
fun interface DeviceLoadSource {
    fun load(): Flow<DeviceLoad>
}

/** Maps the platform's battery saver and thermal state to a [DeviceLoad]. */
object DeviceLoads {

    /**
     * Battery saver on, or a thermal status of [PowerManager.THERMAL_STATUS_SEVERE] or worse, pauses background work.
     * The thermal status (0 none .. 6 shutdown, the order of [ThermalLevel]) is also kept as a level, so moderate heat
     * can step the performance mode down.
     */
    fun of(powerSaveMode: Boolean, thermalStatus: Int): DeviceLoad = DeviceLoad(
        batterySaver = powerSaveMode,
        thermalThrottled = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
        thermalLevel = ThermalLevel.entries[thermalStatus.coerceIn(0, ThermalLevel.entries.lastIndex)],
    )
}

/**
 * [DeviceLoadSource] from [PowerManager]: [PowerManager.isPowerSaveMode] (updated by
 * [PowerManager.ACTION_POWER_SAVE_MODE_CHANGED]) and [PowerManager.getCurrentThermalStatus] (updated by a thermal status
 * listener). The receiver and the listener exist only while the flow is collected, that is while DJ mode is on.
 */
class PowerManagerDeviceLoad(private val context: Context) : DeviceLoadSource {

    override fun load(): Flow<DeviceLoad> = callbackFlow {
        val power = context.getSystemService(PowerManager::class.java)
        if (power == null) {
            trySend(DeviceLoad())
            awaitClose()
            return@callbackFlow
        }
        fun current() = DeviceLoads.of(power.isPowerSaveMode, power.currentThermalStatus)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(current())
            }
        }
        val thermal = PowerManager.OnThermalStatusChangedListener { trySend(current()) }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context), thermal)
        trySend(current())
        awaitClose {
            context.unregisterReceiver(receiver)
            power.removeThermalStatusListener(thermal)
        }
    }.conflate().distinctUntilChanged()
}
