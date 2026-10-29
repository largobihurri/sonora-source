package io.sonora.feature.settings.audio

import io.sonora.core.designsystem.text.Strings
import io.sonora.core.domain.playback.output.AudioOutputDevice
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.BitPerfectAvailability
import io.sonora.core.domain.playback.output.BitPerfectMode
import io.sonora.core.domain.playback.output.Kilohertz
import io.sonora.core.domain.playback.output.MixerOption
import io.sonora.core.domain.playback.output.OutputDeviceKind
import io.sonora.core.domain.playback.output.OutputEncoding
import io.sonora.feature.settings.R

/** User-facing copy of Settings > Audio output from string resources, kept pure so it is unit-tested. */
object AudioOutputText {
    /** The row's value: the title of the device that is heard, or "None". */
    fun summary(snapshot: AudioOutputSnapshot, strings: Strings): String {
        val device = snapshot.heard ?: return strings.get(R.string.audio_output_none)
        return title(device, strings)
    }

    /**
     * What the device is called: "Phone speaker", "Wired headphones", "USB: <product>", "Bluetooth: <name>", "HDMI: <name>";
     * a nameless device is titled by its kind. The phone's own speaker and a headphone jack carry the phone's model name,
     * which says nothing, so they are titled by type alone.
     */
    fun title(device: AudioOutputDevice, strings: Strings): String {
        val name = device.productName.trim()
        return when (device.kind) {
            OutputDeviceKind.SPEAKER, OutputDeviceKind.WIRED -> kindName(device.kind, strings)
            OutputDeviceKind.OTHER -> name.ifEmpty { kindName(device.kind, strings) }
            OutputDeviceKind.USB, OutputDeviceKind.BLUETOOTH, OutputDeviceKind.HDMI ->
                if (name.isEmpty()) kindName(device.kind, strings) else strings.get(R.string.audio_output_title_named, shortKindName(device.kind, strings), name)
        }
    }

    private fun shortKindName(kind: OutputDeviceKind, strings: Strings): String =
        if (kind == OutputDeviceKind.USB) strings.get(R.string.audio_output_kind_usb_short) else kindName(kind, strings)

    /** One line for a device the user could switch to: its sample rates and its deepest bit depth, "44.1–192 kHz · 32-bit". */
    fun otherSummary(device: AudioOutputDevice, strings: Strings): String {
        val parts = listOfNotNull(
            Kilohertz.label(device.sampleRates, strings.locale),
            device.deepestEncoding?.let { encodingName(it, strings) },
        )
        return if (parts.isEmpty()) strings.get(R.string.audio_output_not_reported) else parts.joinToString(" · ")
    }

    fun kindName(kind: OutputDeviceKind, strings: Strings): String = strings.get(
        when (kind) {
            OutputDeviceKind.USB -> R.string.audio_output_kind_usb
            OutputDeviceKind.WIRED -> R.string.audio_output_kind_wired
            OutputDeviceKind.BLUETOOTH -> R.string.audio_output_kind_bluetooth
            OutputDeviceKind.HDMI -> R.string.audio_output_kind_hdmi
            OutputDeviceKind.SPEAKER -> R.string.audio_output_kind_speaker
            OutputDeviceKind.OTHER -> R.string.audio_output_kind_other
        },
    )

    /** "44.1–192 kHz", "44.1 · 48 · 96 kHz", "48 kHz", or "Not reported"; never broken across lines. */
    fun sampleRates(device: AudioOutputDevice, strings: Strings): String =
        Kilohertz.label(device.sampleRates, strings.locale) ?: strings.get(R.string.audio_output_not_reported)

    /** The PCM bit depths the device reports, shallow to deep, or "Not reported". */
    fun bitDepths(device: AudioOutputDevice, strings: Strings): String =
        device.encodings.filter { it.bits != null }.joinToString(", ") { encodingName(it, strings) }
            .ifEmpty { strings.get(R.string.audio_output_not_reported) }

    fun channels(device: AudioOutputDevice, strings: Strings): String =
        device.channelCounts.joinToString(", ").ifEmpty { strings.get(R.string.audio_output_not_reported) }

    fun encodingName(encoding: OutputEncoding, strings: Strings): String = strings.get(
        when (encoding) {
            OutputEncoding.PCM_8 -> R.string.audio_output_pcm_8
            OutputEncoding.PCM_16 -> R.string.audio_output_pcm_16
            OutputEncoding.PCM_24_PACKED -> R.string.audio_output_pcm_24
            OutputEncoding.PCM_32 -> R.string.audio_output_pcm_32
            OutputEncoding.PCM_FLOAT -> R.string.audio_output_pcm_float
            OutputEncoding.OTHER -> R.string.audio_output_not_reported
        },
    )

    /** "96 kHz · 24-bit · 2 ch". */
    fun mixerFormat(option: MixerOption, strings: Strings): String =
        strings.get(R.string.audio_output_mixer_format, Kilohertz.format(option.sampleRate, strings.locale), encodingName(option.encoding, strings), option.channelCount)

    fun mixerBehaviour(option: MixerOption, strings: Strings): String =
        strings.get(if (option.bitPerfect) R.string.audio_output_mixer_bit_perfect else R.string.audio_output_mixer_default)

    /** The short answer to "is bit-perfect on offer for the device that is heard". */
    fun bitPerfectStatus(snapshot: AudioOutputSnapshot, strings: Strings): String = strings.get(
        when (snapshot.bitPerfect) {
            BitPerfectAvailability.AVAILABLE -> R.string.audio_output_bp_offered
            BitPerfectAvailability.DEVICE_WITHOUT_BIT_PERFECT -> R.string.audio_output_bp_not_offered
            BitPerfectAvailability.NO_USB_DEVICE -> R.string.audio_output_bp_no_usb
            BitPerfectAvailability.ANDROID_TOO_OLD -> R.string.audio_output_bp_android_old
        },
    )

    /** The explanation under the Bit-perfect USB output switch: what it does, that it waits for a device, or that it is active. */
    fun bitPerfectSwitchFooter(enabled: Boolean, snapshot: AudioOutputSnapshot, strings: Strings): String = strings.get(
        when {
            !enabled -> R.string.audio_output_bp_switch_off
            BitPerfectMode.armed(enabled = true, snapshot) -> R.string.audio_output_bp_switch_active
            else -> R.string.audio_output_bp_switch_waiting
        },
    )

    /** One device's own bit-perfect offer; a device that is not a USB one has none to give. */
    fun deviceBitPerfect(device: AudioOutputDevice, mixerApi: Boolean, strings: Strings): String = strings.get(
        when {
            !mixerApi -> R.string.audio_output_bp_android_old
            device.offersBitPerfect -> R.string.audio_output_bp_offered
            else -> R.string.audio_output_bp_not_offered
        },
    )

    /** The device's mixer formats for display: bit-perfect ones first, then shallow to deep, low to high rate and channels. */
    fun sortedMixerOptions(device: AudioOutputDevice): List<MixerOption> = device.mixerOptions.sortedWith(
        compareByDescending<MixerOption> { it.bitPerfect }
            .thenBy { it.encoding.bits ?: Int.MAX_VALUE }
            .thenBy { it.encoding == OutputEncoding.PCM_FLOAT }
            .thenBy { it.sampleRate }
            .thenBy { it.channelCount },
    )

    /** The explanation under the details: what bit-perfect needs, or what it found. */
    fun bitPerfectFooter(snapshot: AudioOutputSnapshot, strings: Strings): String = strings.get(
        when (snapshot.bitPerfect) {
            BitPerfectAvailability.AVAILABLE -> R.string.audio_output_bp_footer_offered
            BitPerfectAvailability.DEVICE_WITHOUT_BIT_PERFECT -> R.string.audio_output_bp_footer_not_offered
            BitPerfectAvailability.NO_USB_DEVICE -> R.string.audio_output_bp_footer_no_usb
            BitPerfectAvailability.ANDROID_TOO_OLD -> R.string.audio_output_bp_footer_android_old
        },
    )
}
