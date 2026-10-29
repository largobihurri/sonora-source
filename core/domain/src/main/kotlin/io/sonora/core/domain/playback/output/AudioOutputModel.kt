package io.sonora.core.domain.playback.output

/** The kind of audio output a device is, which decides which one is heard when Android gives no route. */
enum class OutputDeviceKind(val logName: String) {
    /** A USB audio device (DAC, audio interface, USB headset), the only kind that can play bit-perfect. */
    USB("usb"),
    WIRED("wired"),
    BLUETOOTH("bluetooth"),
    HDMI("hdmi"),
    SPEAKER("speaker"),
    OTHER("other"),
}

/**
 * What an output is for. Android lists every sink the audio HAL has, including ones media never plays on: only [MEDIA]
 * outputs are shown as places the user could send music to.
 */
enum class OutputPurpose {
    /** An output music plays on: speaker, wired, USB, Bluetooth A2DP / LE audio, HDMI, hearing aid, dock. */
    MEDIA,

    /** An output for calls: the earpiece, Bluetooth SCO, the telephony line. */
    TELEPHONY,

    /** The built-in speaker's protected twin (`TYPE_BUILTIN_SPEAKER_SAFE`), a duplicate of the speaker. */
    SPEAKER_SAFE,

    /** System plumbing (bus, remote submix, echo reference, FM) and anything Android does not name. */
    INTERNAL,
}

/** A PCM encoding an output device lists, or [OTHER] for anything else (compressed formats, unknown codes). */
enum class OutputEncoding(val bits: Int?) {
    PCM_8(8),
    PCM_16(16),

    /** 24-bit samples packed in three bytes. */
    PCM_24_PACKED(24),
    PCM_32(32),
    PCM_FLOAT(32),
    OTHER(null),
}

/**
 * One format an output device can be asked to mix in (Android 14's `AudioMixerAttributes`): [bitPerfect] says whether
 * the mixer then passes a matching track's samples through unchanged.
 */
data class MixerOption(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: OutputEncoding,
    val bitPerfect: Boolean,
) {
    /** "96000Hz/2ch/PCM_24_PACKED bit-perfect": one line for logs and for the details sheet's technical view. */
    fun describe(): String = "${sampleRate}Hz/${channelCount}ch/${encoding.name} ${if (bitPerfect) "bit-perfect" else "default"}"
}

/**
 * What one audio output device reports. Empty lists mean the device does not say (Android reports "any" that way).
 * [mixerOptions] are the mixer formats Android 14+ offers for the device (empty before that), [activeMixer] the one
 * currently preferred for it. [purpose] says whether music plays on it ([OutputPurpose.MEDIA]) or it is for calls or the system.
 */
data class AudioOutputDevice(
    val id: Int,
    val kind: OutputDeviceKind,
    val productName: String,
    val sampleRates: List<Int>,
    val encodings: List<OutputEncoding>,
    val channelCounts: List<Int>,
    val mixerOptions: List<MixerOption>,
    val activeMixer: MixerOption?,
    val purpose: OutputPurpose = OutputPurpose.MEDIA,
) {
    /** The same output as [other] in the user's eyes: the same kind and name (ignoring case and padding). */
    internal fun sameOutputAs(other: AudioOutputDevice): Boolean =
        kind == other.kind && productName.trim().equals(other.productName.trim(), ignoreCase = true)

    /** This device with what the [others] report added: rates, encodings, channels and mixer formats, each once. */
    internal fun mergedWith(others: List<AudioOutputDevice>): AudioOutputDevice {
        if (others.isEmpty()) return this
        val all = listOf(this) + others
        return copy(
            sampleRates = all.flatMap { it.sampleRates }.distinct().sorted(),
            encodings = all.flatMap { it.encodings }.distinct().sortedBy { it.ordinal },
            channelCounts = all.flatMap { it.channelCounts }.distinct().sorted(),
            mixerOptions = all.flatMap { it.mixerOptions }.distinct(),
            activeMixer = all.firstNotNullOfOrNull { it.activeMixer },
        )
    }

    /** The mixer formats that pass samples through unchanged. */
    val bitPerfectOptions: List<MixerOption> get() = mixerOptions.filter { it.bitPerfect }

    val offersBitPerfect: Boolean get() = mixerOptions.any { it.bitPerfect }

    /** Lowest to highest listed sample rate, or `null` when the device lists none. */
    val sampleRateRange: IntRange? get() = sampleRates.filter { it > 0 }.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }

    /** The widest listed PCM encoding by bit depth (float counts as 32-bit and wins ties), or `null` when none is listed. */
    val deepestEncoding: OutputEncoding?
        get() = encodings.filter { it.bits != null }
            .maxWithOrNull(compareBy<OutputEncoding> { it.bits }.thenBy { it == OutputEncoding.PCM_FLOAT })
}

/** Why bit-perfect output is, or is not, on offer for the device that is heard. */
enum class BitPerfectAvailability {
    /** Android before 14 has no preferred mixer attributes. */
    ANDROID_TOO_OLD,

    /** The device that is heard is not a USB audio device. */
    NO_USB_DEVICE,

    /** A USB device is heard but it offers no bit-perfect mixer format. */
    DEVICE_WITHOUT_BIT_PERFECT,
    AVAILABLE,
}

/**
 * The audio outputs Android lists right now. [routedIds] are the ids of the devices Android routes media to (empty
 * when it cannot say), [mixerApi] whether this Android version can list mixer attributes at all (API 34).
 */
data class AudioOutputSnapshot(
    val devices: List<AudioOutputDevice>,
    val routedIds: Set<Int> = emptySet(),
    val mixerApi: Boolean = false,
) {
    /**
     * The device that is heard: the routed one when known, else the best kind of device listed (USB first), among the
     * outputs music plays on when there are any.
     */
    val primary: AudioOutputDevice?
        get() = devices.firstOrNull { it.id in routedIds } ?: devices.filter { it.purpose == OutputPurpose.MEDIA }
            .ifEmpty { devices }
            .withIndex().minWithOrNull(compareBy({ it.value.kind.ordinal }, { it.index }))?.value

    /** [primary] as the panel shows it: the media outputs of the same kind and name folded into it. */
    val heard: AudioOutputDevice?
        get() = primary?.let { heard -> heard.mergedWith(devices.filter { it !== heard && it.purpose == OutputPurpose.MEDIA && it.sameOutputAs(heard) }) }

    /**
     * The media outputs, other than [heard], the user could send music to: best kind first, those of the same kind and
     * name listed once with their capabilities merged. Calls, speaker-safe, bus and similar outputs are left out.
     */
    val otherDevices: List<AudioOutputDevice>
        get() {
            val heard = primary ?: return emptyList()
            val candidates = devices.filter { it.purpose == OutputPurpose.MEDIA && !it.sameOutputAs(heard) }
            val groups = mutableListOf<MutableList<AudioOutputDevice>>()
            for (device in candidates) {
                val group = groups.firstOrNull { it.first().sameOutputAs(device) }
                if (group != null) group += device else groups += mutableListOf(device)
            }
            return groups.map { it.first().mergedWith(it.drop(1)) }.sortedBy { it.kind.ordinal }
        }

    /** Bit-perfect availability for [primary]. */
    val bitPerfect: BitPerfectAvailability
        get() {
            if (!mixerApi) return BitPerfectAvailability.ANDROID_TOO_OLD
            val heard = primary
            return when {
                heard == null || heard.kind != OutputDeviceKind.USB -> BitPerfectAvailability.NO_USB_DEVICE
                heard.offersBitPerfect -> BitPerfectAvailability.AVAILABLE
                else -> BitPerfectAvailability.DEVICE_WITHOUT_BIT_PERFECT
            }
        }

    /** The USB devices in this snapshot that [previous] did not list. */
    fun newUsbDevices(previous: AudioOutputSnapshot): List<AudioOutputDevice> =
        devices.filter { it.kind == OutputDeviceKind.USB && previous.devices.none { old -> old.id == it.id } }

    companion object {
        val EMPTY = AudioOutputSnapshot(emptyList())
    }
}
