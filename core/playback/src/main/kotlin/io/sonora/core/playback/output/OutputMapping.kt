package io.sonora.core.playback.output

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import io.sonora.core.domain.playback.output.OutputDeviceKind
import io.sonora.core.domain.playback.output.OutputEncoding
import io.sonora.core.domain.playback.output.OutputPurpose

/** Android's audio constants as the domain's output model. */
internal object OutputMapping {
    fun kind(type: Int): OutputDeviceKind = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> OutputDeviceKind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> OutputDeviceKind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> OutputDeviceKind.BLUETOOTH
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputDeviceKind.HDMI
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
            OutputDeviceKind.SPEAKER
        else -> OutputDeviceKind.OTHER
    }

    /** What the output is for: music, calls, the speaker's safe twin, or system plumbing (unknown types included). */
    fun purpose(type: Int): OutputPurpose = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_IP,
        -> OutputPurpose.MEDIA
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_TELEPHONY -> OutputPurpose.TELEPHONY
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputPurpose.SPEAKER_SAFE
        else -> OutputPurpose.INTERNAL
    }

    fun encoding(code: Int): OutputEncoding = when (code) {
        AudioFormat.ENCODING_PCM_8BIT -> OutputEncoding.PCM_8
        AudioFormat.ENCODING_PCM_16BIT -> OutputEncoding.PCM_16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputEncoding.PCM_24_PACKED
        AudioFormat.ENCODING_PCM_32BIT -> OutputEncoding.PCM_32
        AudioFormat.ENCODING_PCM_FLOAT -> OutputEncoding.PCM_FLOAT
        else -> OutputEncoding.OTHER
    }

    /** Ascending values without repeats; zero (an unset rate or channel count) is dropped. */
    fun sortedUnique(values: IntArray): List<Int> = values.filter { it > 0 }.distinct().sorted()

    /** The encodings from shallow to deep (the enum's order) without repeats. */
    fun encodings(codes: IntArray): List<OutputEncoding> = codes.map(::encoding).distinct().sortedBy { it.ordinal }
}
