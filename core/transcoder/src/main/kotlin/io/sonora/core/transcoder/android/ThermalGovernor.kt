package io.sonora.core.transcoder.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.conversion.ConversionBudget
import io.sonora.core.domain.conversion.DeviceConditions
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.PerformancePolicy
import io.sonora.core.domain.port.ConversionBudgetSource
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Watches the thermal status (API 29 listener) and battery saver, and turns them into the live [ConversionBudget]
 * for a requested mode through [PerformancePolicy]: moderate heat or battery saver step one level down each,
 * severe heat forces Low Resources with cool-down pauses, and the budget steps back up as the device recovers.
 */
@Singleton
class ThermalGovernor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capacity: DeviceCapacityReader,
) : ConversionBudgetSource {

    private val power: PowerManager = checkNotNull(context.getSystemService(PowerManager::class.java))

    fun currentConditions(): DeviceConditions =
        DeviceConditions(thermalLevelOf(power.currentThermalStatus), power.isPowerSaveMode)

    /** Current conditions first, then every change; registered only while collected. */
    val conditions: Flow<DeviceConditions> = callbackFlow {
        val update = { trySend(currentConditions()); Unit }
        val thermal = PowerManager.OnThermalStatusChangedListener { update() }
        val saver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update()
        }
        power.addThermalStatusListener(Executor(Runnable::run), thermal)
        ContextCompat.registerReceiver(
            context,
            saver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        update()
        awaitClose {
            power.removeThermalStatusListener(thermal)
            context.unregisterReceiver(saver)
        }
    }.conflate().distinctUntilChanged()

    override fun budgets(mode: PerformanceMode): Flow<ConversionBudget> =
        conditions.map { PerformancePolicy.budget(mode, capacity.capacity, it) }.distinctUntilChanged()
}
