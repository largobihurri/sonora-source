package io.sonora.core.domain.conversion

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.PerformanceMode.HIGH_PERFORMANCE
import io.sonora.core.domain.conversion.PerformanceMode.LOW_RESOURCES
import io.sonora.core.domain.conversion.PerformanceMode.NORMAL
import org.junit.Test

class PerformancePolicyTest {

    /** A phone with 8 cores of which 4 are big, and a roomy heap. */
    private val phone = DeviceCapacity(cpuCores = 8, bigCores = 4, memoryClassMb = 256, lowRamDevice = false)
    private val cool = DeviceConditions()

    private fun budget(mode: PerformanceMode, capacity: DeviceCapacity = phone, conditions: DeviceConditions = cool) =
        PerformancePolicy.budget(mode, capacity, conditions)

    @Test
    fun `normal is the default mode`() {
        assertThat(PerformanceMode.DEFAULT).isEqualTo(NORMAL)
    }

    @Test
    fun `low resources converts one file at a time with small chunks, low priority and pauses`() {
        val low = budget(LOW_RESOURCES)

        assertThat(low.mode).isEqualTo(LOW_RESOURCES)
        assertThat(low.concurrentFiles).isEqualTo(1)
        assertThat(low.chunkFrames).isEqualTo(2_048)
        assertThat(low.threadPriority).isEqualTo(PerformancePolicy.PRIORITY_LOW)
        assertThat(low.pauseBetweenFilesMs).isEqualTo(500)
    }

    @Test
    fun `normal uses half the big cores`() {
        val normal = budget(NORMAL)

        assertThat(normal.concurrentFiles).isEqualTo(2)
        assertThat(normal.chunkFrames).isEqualTo(4_096)
        assertThat(normal.threadPriority).isEqualTo(PerformancePolicy.PRIORITY_BACKGROUND)
        assertThat(normal.pauseBetweenFilesMs).isEqualTo(0)
    }

    @Test
    fun `high performance uses all big cores but one`() {
        val high = budget(HIGH_PERFORMANCE)

        assertThat(high.concurrentFiles).isEqualTo(3)
        assertThat(high.chunkFrames).isEqualTo(8_192)
        assertThat(high.threadPriority).isEqualTo(PerformancePolicy.PRIORITY_DEFAULT)
        assertThat(high.pauseBetweenFilesMs).isEqualTo(0)
    }

    @Test
    fun `without big core information all cores count`() {
        val unknown = phone.copy(bigCores = null)

        assertThat(budget(NORMAL, unknown).concurrentFiles).isEqualTo(4)
        assertThat(budget(HIGH_PERFORMANCE, unknown).concurrentFiles).isEqualTo(7)
    }

    @Test
    fun `a single core device always converts one file at a time`() {
        val single = DeviceCapacity(cpuCores = 1, bigCores = null, memoryClassMb = 256, lowRamDevice = false)

        for (mode in PerformanceMode.entries) assertThat(budget(mode, single).concurrentFiles).isEqualTo(1)
    }

    @Test
    fun `the heap limits how many files run at once`() {
        val smallHeap = phone.copy(bigCores = null, memoryClassMb = 64)

        assertThat(budget(HIGH_PERFORMANCE, smallHeap).concurrentFiles).isEqualTo(2)
    }

    @Test
    fun `a low-RAM device converts one file at a time in every mode`() {
        val lowRam = phone.copy(lowRamDevice = true)

        for (mode in PerformanceMode.entries) assertThat(budget(mode, lowRam).concurrentFiles).isEqualTo(1)
    }

    @Test
    fun `no or light heat keeps the requested mode`() {
        for (mode in PerformanceMode.entries) {
            assertThat(PerformancePolicy.effectiveMode(mode, DeviceConditions(ThermalLevel.NONE))).isEqualTo(mode)
            assertThat(PerformancePolicy.effectiveMode(mode, DeviceConditions(ThermalLevel.LIGHT))).isEqualTo(mode)
        }
    }

    @Test
    fun `moderate heat steps one level down`() {
        val moderate = DeviceConditions(ThermalLevel.MODERATE)

        assertThat(PerformancePolicy.effectiveMode(HIGH_PERFORMANCE, moderate)).isEqualTo(NORMAL)
        assertThat(PerformancePolicy.effectiveMode(NORMAL, moderate)).isEqualTo(LOW_RESOURCES)
        assertThat(PerformancePolicy.effectiveMode(LOW_RESOURCES, moderate)).isEqualTo(LOW_RESOURCES)
        assertThat(budget(NORMAL, conditions = moderate)).isEqualTo(budget(LOW_RESOURCES))
    }

    @Test
    fun `battery saver steps one level down and adds to moderate heat`() {
        val saver = DeviceConditions(powerSaveMode = true)

        assertThat(PerformancePolicy.effectiveMode(HIGH_PERFORMANCE, saver)).isEqualTo(NORMAL)
        assertThat(PerformancePolicy.effectiveMode(NORMAL, saver)).isEqualTo(LOW_RESOURCES)
        assertThat(PerformancePolicy.effectiveMode(HIGH_PERFORMANCE, saver.copy(thermal = ThermalLevel.MODERATE)))
            .isEqualTo(LOW_RESOURCES)
    }

    @Test
    fun `severe heat forces low resources with a cool-down pause between files`() {
        val severe = budget(HIGH_PERFORMANCE, conditions = DeviceConditions(ThermalLevel.SEVERE))

        assertThat(severe.mode).isEqualTo(LOW_RESOURCES)
        assertThat(severe.concurrentFiles).isEqualTo(1)
        assertThat(severe.pauseBetweenFilesMs).isEqualTo(5_000)
    }

    @Test
    fun `critical heat or worse waits longer between files`() {
        for (level in listOf(ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN)) {
            val budget = budget(NORMAL, conditions = DeviceConditions(level))
            assertThat(budget.mode).isEqualTo(LOW_RESOURCES)
            assertThat(budget.pauseBetweenFilesMs).isEqualTo(30_000)
        }
    }

    @Test
    fun `the budget steps back up when the device cools down`() {
        val hot = budget(HIGH_PERFORMANCE, conditions = DeviceConditions(ThermalLevel.SEVERE, powerSaveMode = true))
        val cooled = budget(HIGH_PERFORMANCE, conditions = cool)

        assertThat(hot.mode).isEqualTo(LOW_RESOURCES)
        assertThat(cooled).isEqualTo(budget(HIGH_PERFORMANCE))
    }

    @Test
    fun `big cores are those clocked within 75 percent of the fastest`() {
        val primeBigLittle = listOf(3_200_000L, 2_400_000L, 2_400_000L, 2_400_000L, 1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L)

        assertThat(PerformancePolicy.bigCoreCount(primeBigLittle)).isEqualTo(4)
        assertThat(PerformancePolicy.bigCoreCount(List(8) { 2_000_000L })).isEqualTo(8)
        assertThat(PerformancePolicy.bigCoreCount(emptyList())).isNull()
        assertThat(PerformancePolicy.bigCoreCount(listOf(0L, 0L))).isNull()
    }
}
