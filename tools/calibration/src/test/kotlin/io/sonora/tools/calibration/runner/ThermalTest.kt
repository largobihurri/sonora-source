package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalTest {

    @Test
    fun `reads the package temperature from sensors output`() {
        val output = """
            coretemp-isa-0000
            Adapter: ISA adapter
            Package id 0:  +94.0°C  (high = +100.0°C, crit = +100.0°C)
            Core 0:        +91.0°C  (high = +100.0°C, crit = +100.0°C)
        """.trimIndent()
        assertThat(Thermal.packageCelsius(output)).isEqualTo(94.0)
    }

    @Test
    fun `decimals and the first package only`() {
        val output = "Package id 0:  +71.5°C  (high = +100.0°C)\nPackage id 1:  +99.0°C\n"
        assertThat(Thermal.packageCelsius(output)).isEqualTo(71.5)
    }

    @Test
    fun `no package line gives null`() {
        assertThat(Thermal.packageCelsius("Core 0: +50.0°C\n")).isNull()
        assertThat(Thermal.packageCelsius("")).isNull()
    }
}
