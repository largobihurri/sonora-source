package io.sonora.tools.calibration.runner

import java.util.concurrent.TimeUnit

/**
 * The CPU package temperature from `sensors` (lm-sensors), used to pace the calibration run: this machine overheats,
 * so the runner waits between tracks while the package is above the limit.
 */
object Thermal {

    /** The first `Package id N:` temperature in [sensorsOutput], in degrees Celsius, or `null` when there is none. */
    fun packageCelsius(sensorsOutput: String): Double? =
        PACKAGE.find(sensorsOutput)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Runs `sensors` and reads the package temperature; `null` when the tool is missing or says nothing usable. */
    fun readPackageCelsius(): Double? = try {
        val process = ProcessBuilder("sensors").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(SENSORS_TIMEOUT_S, TimeUnit.SECONDS)
        packageCelsius(text)
    } catch (e: Exception) {
        null
    }

    /**
     * Sleeps while the package is above [limitC], checking every [pollSeconds]; reports each wait with [log]. Returns
     * the last reading (or `null` without sensors, after not waiting at all).
     */
    fun waitBelow(limitC: Double, pollSeconds: Long = 15, log: (String) -> Unit = {}): Double? {
        var reading = readPackageCelsius() ?: return null
        while (reading > limitC) {
            log("CPU package at ${reading} C, above $limitC C: waiting ${pollSeconds}s")
            Thread.sleep(pollSeconds * MS_PER_SECOND)
            reading = readPackageCelsius() ?: return null
        }
        return reading
    }

    private val PACKAGE = Regex("Package id \\d+:\\s*\\+?(-?\\d+(?:\\.\\d+)?)")
    private const val SENSORS_TIMEOUT_S = 10L
    private const val MS_PER_SECOND = 1_000L
}
