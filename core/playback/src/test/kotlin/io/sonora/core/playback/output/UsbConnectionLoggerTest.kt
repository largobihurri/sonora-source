package io.sonora.core.playback.output

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.output.AudioOutputDevice
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.MixerOption
import io.sonora.core.domain.playback.output.OutputDeviceKind
import io.sonora.core.domain.playback.output.OutputEncoding
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UsbConnectionLoggerTest {

    private fun device(id: Int, kind: OutputDeviceKind, name: String, mixer: List<MixerOption> = emptyList()) =
        AudioOutputDevice(id, kind, name, listOf(48_000), listOf(OutputEncoding.PCM_16), listOf(2), mixer, null)

    private val speaker = device(1, OutputDeviceKind.SPEAKER, "Speaker")
    private val dac = device(7, OutputDeviceKind.USB, "Scarlett 2i2 USB", listOf(MixerOption(48_000, 2, OutputEncoding.PCM_24_PACKED, bitPerfect = true)))

    private fun snapshot(vararg devices: AudioOutputDevice) = AudioOutputSnapshot(devices.toList(), mixerApi = true)

    @Test
    fun `a USB device already there when logging starts is logged as connected`() = runTest {
        val lines = mutableListOf<String>()

        UsbConnectionLogger(lines::add).collect(flowOf(snapshot(speaker, dac)))

        assertThat(lines).hasSize(1)
        assertThat(lines.single()).startsWith("USB output connected: usb id=7 \"Scarlett 2i2 USB\"")
        assertThat(lines.single()).contains("bitPerfect=true")
    }

    @Test
    fun `connections and removals are logged once each`() = runTest {
        val lines = mutableListOf<String>()
        val history = flowOf(
            snapshot(speaker),
            snapshot(speaker, dac),
            snapshot(speaker, dac),
            snapshot(speaker),
            snapshot(speaker, dac),
        )

        UsbConnectionLogger(lines::add).collect(history)

        assertThat(lines.map { it.substringBefore(':') }).containsExactly(
            "USB output connected",
            "USB output removed",
            "USB output connected",
        ).inOrder()
    }

    @Test
    fun `devices that are not USB are not logged`() = runTest {
        val lines = mutableListOf<String>()

        UsbConnectionLogger(lines::add).collect(flowOf(snapshot(speaker), snapshot(speaker, device(3, OutputDeviceKind.BLUETOOTH, "Buds"))))

        assertThat(lines).isEmpty()
    }
}
