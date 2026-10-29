package io.sonora.core.domain.playback.output

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioOutputSnapshotTest {

    private fun device(
        id: Int,
        kind: OutputDeviceKind,
        name: String = "device $id",
        rates: List<Int> = emptyList(),
        encodings: List<OutputEncoding> = emptyList(),
        channels: List<Int> = emptyList(),
        mixer: List<MixerOption> = emptyList(),
        activeMixer: MixerOption? = null,
    ) = AudioOutputDevice(id, kind, name, rates, encodings, channels, mixer, activeMixer)

    private fun bitPerfect(rate: Int, encoding: OutputEncoding, channels: Int = 2) = MixerOption(rate, channels, encoding, bitPerfect = true)

    private fun snapshot(vararg devices: AudioOutputDevice, routed: Set<Int> = emptySet(), mixerApi: Boolean = true) =
        AudioOutputSnapshot(devices.toList(), routed, mixerApi)

    // The device that is heard.

    @Test
    fun `an empty snapshot has no primary device`() {
        assertThat(AudioOutputSnapshot.EMPTY.primary).isNull()
    }

    @Test
    fun `the device Android routes media to is the primary one`() {
        val usb = device(1, OutputDeviceKind.USB)
        val speaker = device(2, OutputDeviceKind.SPEAKER)

        assertThat(snapshot(usb, speaker, routed = setOf(2)).primary).isEqualTo(speaker)
    }

    @Test
    fun `without a known route the best kind of device is the primary one`() {
        val speaker = device(1, OutputDeviceKind.SPEAKER)
        val bluetooth = device(2, OutputDeviceKind.BLUETOOTH)
        val wired = device(3, OutputDeviceKind.WIRED)
        val usb = device(4, OutputDeviceKind.USB)

        assertThat(snapshot(speaker, bluetooth, wired, usb).primary).isEqualTo(usb)
        assertThat(snapshot(speaker, bluetooth, wired).primary).isEqualTo(wired)
        assertThat(snapshot(speaker, bluetooth).primary).isEqualTo(bluetooth)
        assertThat(snapshot(speaker).primary).isEqualTo(speaker)
    }

    @Test
    fun `a route that names no listed device falls back to the best kind`() {
        val speaker = device(1, OutputDeviceKind.SPEAKER)
        val usb = device(2, OutputDeviceKind.USB)

        assertThat(snapshot(speaker, usb, routed = setOf(99)).primary).isEqualTo(usb)
    }

    @Test
    fun `among equal kinds the first listed device wins`() {
        val first = device(1, OutputDeviceKind.USB)
        val second = device(2, OutputDeviceKind.USB)

        assertThat(snapshot(first, second).primary).isEqualTo(first)
    }

    // Bit-perfect offer.

    @Test
    fun `a device offers bit-perfect only through a bit-perfect mixer option`() {
        val plain = MixerOption(48_000, 2, OutputEncoding.PCM_16, bitPerfect = false)
        val offered = bitPerfect(96_000, OutputEncoding.PCM_24_PACKED)

        assertThat(device(1, OutputDeviceKind.USB, mixer = listOf(plain)).offersBitPerfect).isFalse()
        assertThat(device(1, OutputDeviceKind.USB, mixer = listOf(plain, offered)).offersBitPerfect).isTrue()
        assertThat(device(1, OutputDeviceKind.USB, mixer = listOf(plain, offered)).bitPerfectOptions).containsExactly(offered)
    }

    @Test
    fun `availability tells why bit-perfect cannot be offered`() {
        val usbWith = device(1, OutputDeviceKind.USB, mixer = listOf(bitPerfect(48_000, OutputEncoding.PCM_16)))
        val usbWithout = device(2, OutputDeviceKind.USB)
        val speaker = device(3, OutputDeviceKind.SPEAKER)

        assertThat(snapshot(usbWith, mixerApi = false).bitPerfect).isEqualTo(BitPerfectAvailability.ANDROID_TOO_OLD)
        assertThat(snapshot(speaker).bitPerfect).isEqualTo(BitPerfectAvailability.NO_USB_DEVICE)
        assertThat(AudioOutputSnapshot(emptyList(), mixerApi = true).bitPerfect).isEqualTo(BitPerfectAvailability.NO_USB_DEVICE)
        assertThat(snapshot(usbWithout, speaker).bitPerfect).isEqualTo(BitPerfectAvailability.DEVICE_WITHOUT_BIT_PERFECT)
        assertThat(snapshot(usbWith, speaker).bitPerfect).isEqualTo(BitPerfectAvailability.AVAILABLE)
    }

    @Test
    fun `bit-perfect is judged on the device that is heard`() {
        val usb = device(1, OutputDeviceKind.USB, mixer = listOf(bitPerfect(48_000, OutputEncoding.PCM_16)))
        val speaker = device(2, OutputDeviceKind.SPEAKER)

        assertThat(snapshot(usb, speaker, routed = setOf(2)).bitPerfect).isEqualTo(BitPerfectAvailability.NO_USB_DEVICE)
    }

    // Capabilities.

    @Test
    fun `sample rate bounds ignore a device that lists none`() {
        assertThat(device(1, OutputDeviceKind.SPEAKER).sampleRateRange).isNull()
        assertThat(device(1, OutputDeviceKind.USB, rates = listOf(48_000, 44_100, 192_000, 96_000)).sampleRateRange).isEqualTo(44_100..192_000)
    }

    @Test
    fun `the deepest encoding is the widest bit depth listed`() {
        val usb = device(1, OutputDeviceKind.USB, encodings = listOf(OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED, OutputEncoding.PCM_32))

        assertThat(usb.deepestEncoding).isEqualTo(OutputEncoding.PCM_32)
        assertThat(device(1, OutputDeviceKind.SPEAKER).deepestEncoding).isNull()
        assertThat(device(1, OutputDeviceKind.USB, encodings = listOf(OutputEncoding.OTHER)).deepestEncoding).isNull()
    }

    @Test
    fun `encodings know their bit depth`() {
        assertThat(OutputEncoding.PCM_8.bits).isEqualTo(8)
        assertThat(OutputEncoding.PCM_16.bits).isEqualTo(16)
        assertThat(OutputEncoding.PCM_24_PACKED.bits).isEqualTo(24)
        assertThat(OutputEncoding.PCM_32.bits).isEqualTo(32)
        assertThat(OutputEncoding.PCM_FLOAT.bits).isEqualTo(32)
        assertThat(OutputEncoding.OTHER.bits).isNull()
    }

    // USB connections.

    @Test
    fun `only USB devices that were not there before count as newly connected`() {
        val usb = device(1, OutputDeviceKind.USB)
        val other = device(2, OutputDeviceKind.USB)
        val speaker = device(3, OutputDeviceKind.SPEAKER)

        assertThat(snapshot(usb, speaker).newUsbDevices(previous = AudioOutputSnapshot.EMPTY)).containsExactly(usb)
        assertThat(snapshot(usb, speaker).newUsbDevices(previous = snapshot(usb))).isEmpty()
        assertThat(snapshot(usb, other, speaker).newUsbDevices(previous = snapshot(usb, speaker))).containsExactly(other)
    }

    // Log line.

    @Test
    fun `the log line carries everything needed to check a device from logcat`() {
        val usb = device(
            12,
            OutputDeviceKind.USB,
            name = "Scarlett 2i2 USB",
            rates = listOf(44_100, 48_000, 96_000),
            encodings = listOf(OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED),
            channels = listOf(2),
            mixer = listOf(bitPerfect(96_000, OutputEncoding.PCM_24_PACKED), MixerOption(48_000, 2, OutputEncoding.PCM_16, bitPerfect = false)),
        )

        assertThat(AudioOutputLog.describe(usb)).isEqualTo(
            "usb id=12 \"Scarlett 2i2 USB\" rates=[44100, 48000, 96000] encodings=[PCM_16, PCM_24_PACKED] channels=[2] " +
                "mixer=[96000Hz/2ch/PCM_24_PACKED bit-perfect, 48000Hz/2ch/PCM_16 default] bitPerfect=true",
        )
    }

    @Test
    fun `the log line of a device with no mixer attributes says so`() {
        val speaker = device(2, OutputDeviceKind.SPEAKER, name = "Speaker")

        assertThat(AudioOutputLog.describe(speaker)).isEqualTo(
            "speaker id=2 \"Speaker\" rates=[] encodings=[] channels=[] mixer=[] bitPerfect=false",
        )
    }
}
