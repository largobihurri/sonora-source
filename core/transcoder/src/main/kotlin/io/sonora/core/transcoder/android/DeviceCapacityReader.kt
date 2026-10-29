package io.sonora.core.transcoder.android

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.conversion.DeviceCapacity
import io.sonora.core.domain.conversion.PerformancePolicy
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device's [DeviceCapacity] once: available processors, big cores from the per-core maximum clocks in
 * sysfs (when readable; many devices hide them), heap class and low-RAM flag.
 */
@Singleton
class DeviceCapacityReader @Inject constructor(@ApplicationContext private val context: Context) {

    val capacity: DeviceCapacity by lazy {
        val activity = checkNotNull(context.getSystemService(ActivityManager::class.java))
        val cores = Runtime.getRuntime().availableProcessors()
        DeviceCapacity(
            cpuCores = cores,
            bigCores = PerformancePolicy.bigCoreCount(maxFrequenciesKhz())?.coerceAtMost(cores),
            memoryClassMb = activity.memoryClass,
            lowRamDevice = activity.isLowRamDevice,
        )
    }

    private fun maxFrequenciesKhz(): List<Long> = try {
        File(CPU_ROOT).listFiles { file -> CPU_DIR.matches(file.name) }.orEmpty().mapNotNull { cpu ->
            File(cpu, MAX_FREQUENCY).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu"
        const val MAX_FREQUENCY = "cpufreq/cpuinfo_max_freq"
        val CPU_DIR = Regex("cpu\\d+")
    }
}
