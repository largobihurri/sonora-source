package io.sonora.feature.settings.audio

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.output.AudioOutputDevice
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.MixerOption
import io.sonora.core.domain.playback.output.OutputDeviceKind
import io.sonora.core.domain.playback.output.OutputEncoding
import io.sonora.feature.settings.XmlStrings
import org.junit.Test

/** The Audio output section's copy, against the real string files in the three languages. */
class AudioOutputTextTest {

    private val en = XmlStrings.of()
    private val es = XmlStrings.of("-es")
    private val eu = XmlStrings.of("-eu")

    private fun device(
        kind: OutputDeviceKind = OutputDeviceKind.USB,
        name: String = "Scarlett 2i2 USB",
        rates: List<Int> = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000),
        encodings: List<OutputEncoding> = listOf(OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED, OutputEncoding.PCM_32),
        channels: List<Int> = listOf(2),
        mixer: List<MixerOption> = emptyList(),
    ) = AudioOutputDevice(7, kind, name, rates, encodings, channels, mixer, null)

    private fun snapshot(vararg devices: AudioOutputDevice, mixerApi: Boolean = true) = AudioOutputSnapshot(devices.toList(), mixerApi = mixerApi)

    private val offered = MixerOption(96_000, 2, OutputEncoding.PCM_24_PACKED, bitPerfect = true)

    private val nbsp = "\u00A0"

    @Test
    fun `the summary titles the device that is heard by its type and name`() {
        assertThat(AudioOutputText.summary(snapshot(device()), en)).isEqualTo("USB: Scarlett 2i2 USB")
        assertThat(AudioOutputText.summary(snapshot(device()), es)).isEqualTo("USB: Scarlett 2i2 USB")
    }

    @Test
    fun `a device with no name is titled by its kind`() {
        assertThat(AudioOutputText.summary(snapshot(device(name = "")), en)).isEqualTo("USB audio")
        assertThat(AudioOutputText.title(device(OutputDeviceKind.BLUETOOTH, name = " "), en)).isEqualTo("Bluetooth")
    }

    @Test
    fun `the phone speaker and wired headphones are titled by type, never by the phone's model name`() {
        assertThat(AudioOutputText.title(device(OutputDeviceKind.SPEAKER, name = "RMX5011"), en)).isEqualTo("Phone speaker")
        assertThat(AudioOutputText.title(device(OutputDeviceKind.SPEAKER, name = "RMX5011"), es)).isEqualTo("Altavoz del teléfono")
        assertThat(AudioOutputText.title(device(OutputDeviceKind.WIRED, name = "RMX5011"), en)).isEqualTo("Wired headphones")
        assertThat(AudioOutputText.title(device(OutputDeviceKind.WIRED, name = ""), es)).isEqualTo("Auriculares con cable")
    }

    @Test
    fun `Bluetooth and HDMI outputs are titled with their name after the type`() {
        assertThat(AudioOutputText.title(device(OutputDeviceKind.BLUETOOTH, name = "Buds Pro"), en)).isEqualTo("Bluetooth: Buds Pro")
        assertThat(AudioOutputText.title(device(OutputDeviceKind.HDMI, name = "LG TV"), es)).isEqualTo("HDMI: LG TV")
        assertThat(AudioOutputText.title(device(OutputDeviceKind.BLUETOOTH, name = "Buds Pro"), eu)).isEqualTo("Bluetooth: Buds Pro")
    }

    @Test
    fun `an unnamed other output is titled other output and a named one by its name`() {
        assertThat(AudioOutputText.title(device(OutputDeviceKind.OTHER, name = ""), en)).isEqualTo("Other output")
        assertThat(AudioOutputText.title(device(OutputDeviceKind.OTHER, name = "Dock"), en)).isEqualTo("Dock")
    }

    @Test
    fun `with no outputs the summary says none`() {
        assertThat(AudioOutputText.summary(AudioOutputSnapshot.EMPTY, en)).isEqualTo("None")
        assertThat(AudioOutputText.summary(AudioOutputSnapshot.EMPTY, es)).isEqualTo("Ninguno")
    }

    @Test
    fun `sample rates are compact, in the language's number format and never break across lines`() {
        assertThat(AudioOutputText.sampleRates(device(), en)).isEqualTo("44.1–192${nbsp}kHz")
        assertThat(AudioOutputText.sampleRates(device(), es)).isEqualTo("44,1–192${nbsp}kHz")
        assertThat(AudioOutputText.sampleRates(device(rates = listOf(48_000)), en)).isEqualTo("48${nbsp}kHz")
        assertThat(AudioOutputText.sampleRates(device(rates = listOf(8_000, 16_000)), es)).isEqualTo("8${nbsp}·${nbsp}16${nbsp}kHz")
        assertThat(AudioOutputText.sampleRates(device(rates = listOf(22_050, 176_400)), en)).isEqualTo("22.05${nbsp}·${nbsp}176.4${nbsp}kHz")
        assertThat(AudioOutputText.sampleRates(device(rates = emptyList()), en)).isEqualTo("Not reported")
    }

    @Test
    fun `another device reads as a rate range and its deepest bit depth on one line`() {
        assertThat(AudioOutputText.otherSummary(device(), en)).isEqualTo("44.1–192${nbsp}kHz · 32-bit")
        assertThat(AudioOutputText.otherSummary(device(rates = emptyList()), es)).isEqualTo("32 bits")
        assertThat(AudioOutputText.otherSummary(device(rates = emptyList(), encodings = emptyList()), en)).isEqualTo("Not reported")
    }

    @Test
    fun `bit depths list every PCM encoding the device reports`() {
        assertThat(AudioOutputText.bitDepths(device(), en)).isEqualTo("16-bit, 24-bit, 32-bit")
        assertThat(AudioOutputText.bitDepths(device(encodings = listOf(OutputEncoding.PCM_16, OutputEncoding.PCM_FLOAT)), en))
            .isEqualTo("16-bit, 32-bit float")
        assertThat(AudioOutputText.bitDepths(device(encodings = listOf(OutputEncoding.OTHER)), en)).isEqualTo("Not reported")
        assertThat(AudioOutputText.bitDepths(device(encodings = emptyList()), en)).isEqualTo("Not reported")
    }

    @Test
    fun `channels list the counts the device reports`() {
        assertThat(AudioOutputText.channels(device(channels = listOf(2, 8)), en)).isEqualTo("2, 8")
        assertThat(AudioOutputText.channels(device(channels = emptyList()), en)).isEqualTo("Not reported")
    }

    @Test
    fun `a mixer format reads as rate, depth and channels`() {
        assertThat(AudioOutputText.mixerFormat(offered, en)).isEqualTo("96 kHz · 24-bit · 2 ch")
        assertThat(AudioOutputText.mixerFormat(MixerOption(44_100, 2, OutputEncoding.PCM_16, false), es)).isEqualTo("44,1 kHz · 16 bits · 2 can.")
    }

    @Test
    fun `a mixer format says whether it is bit-perfect`() {
        assertThat(AudioOutputText.mixerBehaviour(offered, en)).isEqualTo("Bit-perfect")
        assertThat(AudioOutputText.mixerBehaviour(offered.copy(bitPerfect = false), en)).isEqualTo("Default")
    }

    @Test
    fun `the bit-perfect status explains why it is or is not offered`() {
        assertThat(AudioOutputText.bitPerfectStatus(snapshot(device(mixer = listOf(offered))), en)).isEqualTo("Offered")
        assertThat(AudioOutputText.bitPerfectStatus(snapshot(device()), en)).isEqualTo("Not offered")
        assertThat(AudioOutputText.bitPerfectStatus(snapshot(device(OutputDeviceKind.SPEAKER)), en)).isEqualTo("No USB device")
        assertThat(AudioOutputText.bitPerfectStatus(snapshot(device(), mixerApi = false), en)).isEqualTo("Needs Android 14")
    }

    @Test
    fun `the footer tells what to do about bit-perfect`() {
        assertThat(AudioOutputText.bitPerfectFooter(snapshot(device(OutputDeviceKind.SPEAKER)), en)).contains("Connect a USB audio device")
        assertThat(AudioOutputText.bitPerfectFooter(snapshot(device(), mixerApi = false), en)).contains("Android 14")
    }

    @Test
    fun `every kind of device has a name in every language`() {
        for (strings in listOf(en, es, eu)) {
            OutputDeviceKind.entries.forEach { assertThat(AudioOutputText.kindName(it, strings)).isNotEmpty() }
            OutputEncoding.entries.filter { it.bits != null }.forEach { assertThat(AudioOutputText.encodingName(it, strings)).isNotEmpty() }
        }
    }

    @Test
    fun `each device states its own bit-perfect offer`() {
        val usb = device(mixer = listOf(offered))
        assertThat(AudioOutputText.deviceBitPerfect(usb, mixerApi = true, en)).isEqualTo("Offered")
        assertThat(AudioOutputText.deviceBitPerfect(device(), mixerApi = true, en)).isEqualTo("Not offered")
        assertThat(AudioOutputText.deviceBitPerfect(device(OutputDeviceKind.SPEAKER), mixerApi = true, en)).isEqualTo("Not offered")
        assertThat(AudioOutputText.deviceBitPerfect(usb, mixerApi = false, en)).isEqualTo("Needs Android 14")
    }

    @Test
    fun `mixer formats list bit-perfect ones first, then by depth, rate and channels`() {
        val plain16 = MixerOption(48_000, 2, OutputEncoding.PCM_16, bitPerfect = false)
        val bp24at96 = MixerOption(96_000, 2, OutputEncoding.PCM_24_PACKED, bitPerfect = true)
        val bp24at48 = MixerOption(48_000, 2, OutputEncoding.PCM_24_PACKED, bitPerfect = true)
        val bp16 = MixerOption(48_000, 2, OutputEncoding.PCM_16, bitPerfect = true)
        val plain16at44 = MixerOption(44_100, 2, OutputEncoding.PCM_16, bitPerfect = false)

        val sorted = AudioOutputText.sortedMixerOptions(device(mixer = listOf(plain16, bp24at96, plain16at44, bp16, bp24at48)))

        assertThat(sorted).containsExactly(bp16, bp24at48, bp24at96, plain16at44, plain16).inOrder()
    }

    @Test
    fun `the bit-perfect switch footer says what it does while it is off`() {
        val text = AudioOutputText.bitPerfectSwitchFooter(enabled = false, snapshot(device(mixer = listOf(offered))), en)

        assertThat(text).startsWith("Sends each song's samples to a USB audio device unchanged")
        assertThat(text).contains("Android 14")
    }

    @Test
    fun `the bit-perfect switch footer says it is active and which effects are off`() {
        val text = AudioOutputText.bitPerfectSwitchFooter(enabled = true, snapshot(device(mixer = listOf(offered))), en)

        assertThat(text).contains("The equalizer, volume normalisation, crossfade, speed, pitch and DJ tempo are off")
        assertThat(text).contains("play the normal way")
    }

    @Test
    fun `the bit-perfect switch footer says it waits for a device when none offers it`() {
        val text = AudioOutputText.bitPerfectSwitchFooter(enabled = true, snapshot(device()), en)

        assertThat(text).contains("takes effect when a USB audio device")
    }

    @Test
    fun `the bit-perfect copy exists in Spanish and Basque`() {
        for (strings in listOf(es, eu)) {
            assertThat(AudioOutputText.bitPerfectSwitchFooter(true, snapshot(device(mixer = listOf(offered))), strings)).isNotEmpty()
            assertThat(AudioOutputText.bitPerfectSwitchFooter(true, snapshot(device()), strings)).isNotEmpty()
            assertThat(AudioOutputText.bitPerfectSwitchFooter(false, snapshot(device()), strings)).isNotEmpty()
        }
        assertThat(AudioOutputText.bitPerfectSwitchFooter(false, snapshot(device()), es)).contains("Android 14")
    }
}
