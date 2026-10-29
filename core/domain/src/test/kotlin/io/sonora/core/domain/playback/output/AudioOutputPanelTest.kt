package io.sonora.core.domain.playback.output

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/** What the output panel lists: the device that is heard, and the other devices media could be switched to. */
class AudioOutputPanelTest {

    private fun device(
        id: Int,
        kind: OutputDeviceKind,
        name: String = "device $id",
        rates: List<Int> = emptyList(),
        encodings: List<OutputEncoding> = emptyList(),
        channels: List<Int> = emptyList(),
        mixer: List<MixerOption> = emptyList(),
        activeMixer: MixerOption? = null,
        purpose: OutputPurpose = OutputPurpose.MEDIA,
    ) = AudioOutputDevice(id, kind, name, rates, encodings, channels, mixer, activeMixer, purpose)

    private fun snapshot(vararg devices: AudioOutputDevice, routed: Set<Int> = emptySet()) = AudioOutputSnapshot(devices.toList(), routed, mixerApi = true)

    // The phone of the device review: a stereo speaker, a second 1-channel speaker, and a telephony output.
    private val speaker = device(1, OutputDeviceKind.SPEAKER, "RMX5011", listOf(48_000), listOf(OutputEncoding.PCM_16), listOf(2))
    private val speakerSafe = device(
        2, OutputDeviceKind.SPEAKER, "RMX5011", listOf(48_000), listOf(OutputEncoding.PCM_16), listOf(1), purpose = OutputPurpose.SPEAKER_SAFE,
    )
    private val telephony = device(
        3, OutputDeviceKind.OTHER, "RMX5011", listOf(8_000, 16_000), listOf(OutputEncoding.PCM_16), listOf(1, 2), purpose = OutputPurpose.TELEPHONY,
    )

    // The device that is heard when Android names no route.

    @Test
    fun `without a route a device media plays on wins over a better kind that is not for media`() {
        val sco = device(1, OutputDeviceKind.BLUETOOTH, "Buds", purpose = OutputPurpose.TELEPHONY)
        val phone = device(2, OutputDeviceKind.SPEAKER, "RMX5011")

        assertThat(snapshot(sco, phone).primary).isEqualTo(phone)
    }

    @Test
    fun `when only devices that are not for media are listed the best of them is still the primary one`() {
        val earpiece = device(1, OutputDeviceKind.SPEAKER, "Earpiece", purpose = OutputPurpose.TELEPHONY)

        assertThat(snapshot(earpiece).primary).isEqualTo(earpiece)
    }

    // The device that is heard, as the panel shows it.

    @Test
    fun `an empty snapshot has no heard device and nothing to switch to`() {
        assertThat(AudioOutputSnapshot.EMPTY.heard).isNull()
        assertThat(AudioOutputSnapshot.EMPTY.otherDevices).isEmpty()
    }

    @Test
    fun `the heard device is the route and keeps its facts`() {
        val snap = snapshot(speaker, speakerSafe, telephony, routed = setOf(1))

        assertThat(snap.heard).isEqualTo(speaker)
    }

    @Test
    fun `a heard device is merged with the media outputs of the same type and name`() {
        val mono = device(5, OutputDeviceKind.SPEAKER, "rmx5011 ", listOf(44_100), listOf(OutputEncoding.PCM_24_PACKED), listOf(1))

        val heard = snapshot(speaker, mono, routed = setOf(1)).heard!!

        assertThat(heard.id).isEqualTo(1)
        assertThat(heard.sampleRates).containsExactly(44_100, 48_000).inOrder()
        assertThat(heard.encodings).containsExactly(OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED).inOrder()
        assertThat(heard.channelCounts).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `a heard device does not absorb a speaker-safe or telephony output of the same name`() {
        val heard = snapshot(speaker, speakerSafe, telephony, routed = setOf(1)).heard!!

        assertThat(heard.channelCounts).containsExactly(2)
        assertThat(heard.sampleRates).containsExactly(48_000)
    }

    @Test
    fun `merging keeps every mixer option once and the first active mixer`() {
        val option = MixerOption(48_000, 2, OutputEncoding.PCM_16, bitPerfect = true)
        val other = MixerOption(96_000, 2, OutputEncoding.PCM_24_PACKED, bitPerfect = true)
        val a = device(1, OutputDeviceKind.USB, "DAC", mixer = listOf(option), activeMixer = null)
        val b = device(2, OutputDeviceKind.USB, "DAC", mixer = listOf(option, other), activeMixer = other)

        val heard = snapshot(a, b, routed = setOf(1)).heard!!

        assertThat(heard.mixerOptions).containsExactly(option, other).inOrder()
        assertThat(heard.activeMixer).isEqualTo(other)
    }

    // The other devices.

    @Test
    fun `the phone's duplicate speaker and telephony output are not offered as other devices`() {
        assertThat(snapshot(speaker, speakerSafe, telephony, routed = setOf(1)).otherDevices).isEmpty()
    }

    @Test
    fun `other devices are media outputs the user could switch to, best kind first`() {
        val hdmi = device(10, OutputDeviceKind.HDMI, "TV")
        val bluetooth = device(11, OutputDeviceKind.BLUETOOTH, "Buds")
        val usb = device(12, OutputDeviceKind.USB, "DAC")
        val wired = device(13, OutputDeviceKind.WIRED, "")
        val dock = device(14, OutputDeviceKind.OTHER, "Dock")

        val others = snapshot(speaker, hdmi, bluetooth, dock, usb, wired, routed = setOf(1)).otherDevices

        assertThat(others.map { it.id }).containsExactly(12, 13, 11, 10, 14).inOrder()
    }

    @Test
    fun `the built-in speaker is offered when another device is heard`() {
        val bluetooth = device(11, OutputDeviceKind.BLUETOOTH, "Buds")

        assertThat(snapshot(speaker, bluetooth, routed = setOf(11)).otherDevices).containsExactly(speaker)
    }

    @Test
    fun `devices that are not for media are never offered`() {
        val bluetooth = device(11, OutputDeviceKind.BLUETOOTH, "Buds")
        val sco = device(12, OutputDeviceKind.BLUETOOTH, "Buds", purpose = OutputPurpose.TELEPHONY)
        val bus = device(13, OutputDeviceKind.OTHER, "bus", purpose = OutputPurpose.INTERNAL)

        assertThat(snapshot(speaker, bluetooth, sco, bus, speakerSafe, telephony, routed = setOf(1)).otherDevices).containsExactly(bluetooth)
    }

    @Test
    fun `other devices with the same type and name are listed once with their capabilities merged`() {
        val first = device(20, OutputDeviceKind.USB, "DAC", listOf(44_100, 48_000), listOf(OutputEncoding.PCM_16), listOf(2))
        val second = device(21, OutputDeviceKind.USB, " dac", listOf(96_000), listOf(OutputEncoding.PCM_24_PACKED), listOf(2, 8))
        val differentName = device(22, OutputDeviceKind.USB, "Other DAC")

        val others = snapshot(speaker, first, second, differentName, routed = setOf(1)).otherDevices

        assertThat(others.map { it.id }).containsExactly(20, 22).inOrder()
        assertThat(others[0].sampleRates).containsExactly(44_100, 48_000, 96_000).inOrder()
        assertThat(others[0].encodings).containsExactly(OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED).inOrder()
        assertThat(others[0].channelCounts).containsExactly(2, 8).inOrder()
    }

    @Test
    fun `the heard device is not repeated among the others`() {
        val usb = device(20, OutputDeviceKind.USB, "DAC")
        val usbAgain = device(21, OutputDeviceKind.USB, "DAC")

        assertThat(snapshot(usb, usbAgain, speaker, routed = setOf(20)).otherDevices).containsExactly(speaker)
    }

    // Kilohertz text.

    @Test
    fun `kilohertz are written without trailing zeros in the locale's number format`() {
        assertThat(Kilohertz.format(44_100, Locale.ENGLISH)).isEqualTo("44.1")
        assertThat(Kilohertz.format(44_100, Locale.forLanguageTag("es"))).isEqualTo("44,1")
        assertThat(Kilohertz.format(48_000, Locale.ENGLISH)).isEqualTo("48")
        assertThat(Kilohertz.format(22_050, Locale.ENGLISH)).isEqualTo("22.05")
    }

    private val nbsp = " "

    @Test
    fun `a few sample rates are listed and many are a range, all on one line`() {
        val es = Locale.forLanguageTag("es")

        assertThat(Kilohertz.label(listOf(48_000), es)).isEqualTo("48${nbsp}kHz")
        assertThat(Kilohertz.label(listOf(8_000, 16_000), es)).isEqualTo("8${nbsp}·${nbsp}16${nbsp}kHz")
        assertThat(Kilohertz.label(listOf(44_100, 48_000, 96_000), es)).isEqualTo("44,1${nbsp}·${nbsp}48${nbsp}·${nbsp}96${nbsp}kHz")
        assertThat(Kilohertz.label(listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000), es)).isEqualTo("44,1–192${nbsp}kHz")
    }

    @Test
    fun `sample rates are sorted and zeros dropped, and none gives null`() {
        assertThat(Kilohertz.label(listOf(96_000, 0, 44_100, 44_100), Locale.ENGLISH)).isEqualTo("44.1${nbsp}·${nbsp}96${nbsp}kHz")
        assertThat(Kilohertz.label(emptyList(), Locale.ENGLISH)).isNull()
    }
}
