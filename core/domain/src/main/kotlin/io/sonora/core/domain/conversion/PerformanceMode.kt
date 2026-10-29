package io.sonora.core.domain.conversion

/**
 * How hard the converter may work. Output quality is identical in every mode (same resampler, dither and bit
 * depth); only the load changes.
 */
enum class PerformanceMode {
    LOW_RESOURCES,
    NORMAL,
    HIGH_PERFORMANCE,
    ;

    companion object {
        val DEFAULT: PerformanceMode = NORMAL
    }
}

/** The load a batch may put on the device right now. */
data class ConversionBudget(
    /** The mode these numbers come from: the requested one, or lower while the device is hot or saving battery. */
    val mode: PerformanceMode,
    /** Files converted in parallel. */
    val concurrentFiles: Int,
    /** Frames per decode/resample/encode block; bounds the per-file working memory. */
    val chunkFrames: Int,
    /** Linux nice value for worker threads, as `android.os.Process.setThreadPriority` takes it (-20..19). */
    val threadPriority: Int,
    /** Rest between two files, letting the device cool down. */
    val pauseBetweenFilesMs: Long,
)

/** What the device offers, read once. */
data class DeviceCapacity(
    /** Processors available to the app. */
    val cpuCores: Int,
    /** Fast cores, when the clock speeds reveal them; `null` when unknown. */
    val bigCores: Int?,
    /** Per-app heap limit (`ActivityManager.memoryClass`). */
    val memoryClassMb: Int,
    val lowRamDevice: Boolean,
)

/** Thermal pressure, mirroring `PowerManager.THERMAL_STATUS_*` (API 29). */
enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

/** What the device reports right now. */
data class DeviceConditions(
    val thermal: ThermalLevel = ThermalLevel.NONE,
    val powerSaveMode: Boolean = false,
)

/** Pure mapping from a requested mode, the device and its current conditions to a [ConversionBudget]. */
object PerformancePolicy {

    /** `Process.THREAD_PRIORITY_DEFAULT`. */
    const val PRIORITY_DEFAULT: Int = 0

    /** `Process.THREAD_PRIORITY_BACKGROUND`. */
    const val PRIORITY_BACKGROUND: Int = 10

    /** Between background and `THREAD_PRIORITY_LOWEST` (19): yields to everything else without starving. */
    const val PRIORITY_LOW: Int = 15

    /** The requested mode, stepped down one level each for moderate heat and battery saver; severe heat forces low. */
    fun effectiveMode(requested: PerformanceMode, conditions: DeviceConditions): PerformanceMode {
        if (conditions.thermal >= ThermalLevel.SEVERE) return PerformanceMode.LOW_RESOURCES
        var steps = 0
        if (conditions.thermal >= ThermalLevel.MODERATE) steps++
        if (conditions.powerSaveMode) steps++
        return PerformanceMode.entries[maxOf(0, requested.ordinal - steps)]
    }

    fun budget(requested: PerformanceMode, capacity: DeviceCapacity, conditions: DeviceConditions = DeviceConditions()): ConversionBudget {
        val mode = effectiveMode(requested, conditions)
        val cores = maxOf(1, capacity.bigCores ?: capacity.cpuCores)
        val memoryCap = if (capacity.lowRamDevice) 1 else maxOf(1, capacity.memoryClassMb / HEAP_MB_PER_FILE)
        fun files(wanted: Int) = wanted.coerceIn(1, memoryCap)
        val base = when (mode) {
            PerformanceMode.LOW_RESOURCES -> ConversionBudget(mode, 1, 2_048, PRIORITY_LOW, LOW_PAUSE_MS)
            PerformanceMode.NORMAL -> ConversionBudget(mode, files(cores / 2), 4_096, PRIORITY_BACKGROUND, 0)
            PerformanceMode.HIGH_PERFORMANCE -> ConversionBudget(mode, files(cores - 1), 8_192, PRIORITY_DEFAULT, 0)
        }
        val coolDown = when {
            conditions.thermal >= ThermalLevel.CRITICAL -> CRITICAL_PAUSE_MS
            conditions.thermal >= ThermalLevel.SEVERE -> SEVERE_PAUSE_MS
            else -> return base
        }
        return base.copy(concurrentFiles = 1, pauseBetweenFilesMs = coolDown)
    }

    /**
     * How many cores run within 75 % of the fastest core's maximum clock ([maxFrequenciesKhz], one per core, as
     * `cpufreq/cpuinfo_max_freq` reports them); `null` when nothing usable was read.
     */
    fun bigCoreCount(maxFrequenciesKhz: List<Long>): Int? {
        val fastest = maxFrequenciesKhz.maxOrNull()?.takeIf { it > 0 } ?: return null
        return maxFrequenciesKhz.count { it * 4 >= fastest * 3 }
    }

    /**
     * Heap set aside per file in flight: decoder buffers and codec, resampler tables (up to ~2 MB at 768 kHz) and
     * blocks, with room for the app itself.
     */
    private const val HEAP_MB_PER_FILE = 32
    private const val LOW_PAUSE_MS = 500L
    private const val SEVERE_PAUSE_MS = 5_000L
    private const val CRITICAL_PAUSE_MS = 30_000L
}
