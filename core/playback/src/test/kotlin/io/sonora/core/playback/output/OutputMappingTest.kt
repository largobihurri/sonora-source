package io.sonora.core.playback.output

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.output.OutputDeviceKind
import io.sonora.core.domain.playback.output.OutputEncoding
import io.sonora.core.domain.playback.output.OutputPurpose
import org.junit.Test

class OutputMappingTest {

    @Test
    fun `USB devices and headsets are USB`() {
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_USB_DEVICE)).isEqualTo(OutputDeviceKind.USB)
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_USB_HEADSET)).isEqualTo(OutputDeviceKind.USB)
    }

    @Test
    fun `jacks and lines are wired`() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
        ).forEach { assertThat(OutputMapping.kind(it)).isEqualTo(OutputDeviceKind.WIRED) }
    }

    @Test
    fun `Bluetooth, HDMI and built-in outputs map to their kind`() {
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).isEqualTo(OutputDeviceKind.BLUETOOTH)
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isEqualTo(OutputDeviceKind.BLUETOOTH)
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_BLE_HEADSET)).isEqualTo(OutputDeviceKind.BLUETOOTH)
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_HDMI)).isEqualTo(OutputDeviceKind.HDMI)
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_HDMI_ARC)).isEqualTo(OutputDeviceKind.HDMI)
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isEqualTo(OutputDeviceKind.SPEAKER)
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)).isEqualTo(OutputDeviceKind.SPEAKER)
    }

    @Test
    fun `anything else is other`() {
        assertThat(OutputMapping.kind(AudioDeviceInfo.TYPE_TELEPHONY)).isEqualTo(OutputDeviceKind.OTHER)
        assertThat(OutputMapping.kind(-1)).isEqualTo(OutputDeviceKind.OTHER)
    }

    @Test
    fun `outputs music plays on are media`() {
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_DOCK,
        ).forEach { assertThat(OutputMapping.purpose(it)).isEqualTo(OutputPurpose.MEDIA) }
    }

    @Test
    fun `the earpiece, Bluetooth SCO and the telephony line are for calls`() {
        listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_TELEPHONY)
            .forEach { assertThat(OutputMapping.purpose(it)).isEqualTo(OutputPurpose.TELEPHONY) }
    }

    @Test
    fun `the speaker-safe twin is its own purpose and system outputs are internal`() {
        assertThat(OutputMapping.purpose(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)).isEqualTo(OutputPurpose.SPEAKER_SAFE)
        listOf(AudioDeviceInfo.TYPE_BUS, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_FM, 28, -1)
            .forEach { assertThat(OutputMapping.purpose(it)).isEqualTo(OutputPurpose.INTERNAL) }
    }

    @Test
    fun `PCM encodings map and everything else is other`() {
        assertThat(OutputMapping.encoding(AudioFormat.ENCODING_PCM_8BIT)).isEqualTo(OutputEncoding.PCM_8)
        assertThat(OutputMapping.encoding(AudioFormat.ENCODING_PCM_16BIT)).isEqualTo(OutputEncoding.PCM_16)
        assertThat(OutputMapping.encoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)).isEqualTo(OutputEncoding.PCM_24_PACKED)
        assertThat(OutputMapping.encoding(AudioFormat.ENCODING_PCM_32BIT)).isEqualTo(OutputEncoding.PCM_32)
        assertThat(OutputMapping.encoding(AudioFormat.ENCODING_PCM_FLOAT)).isEqualTo(OutputEncoding.PCM_FLOAT)
        assertThat(OutputMapping.encoding(AudioFormat.ENCODING_AC3)).isEqualTo(OutputEncoding.OTHER)
    }

    @Test
    fun `lists are sorted without repeats and zero rates or channels are dropped`() {
        assertThat(OutputMapping.sortedUnique(intArrayOf(96_000, 44_100, 48_000, 44_100, 0))).containsExactly(44_100, 48_000, 96_000).inOrder()
        assertThat(OutputMapping.sortedUnique(intArrayOf())).isEmpty()
    }

    @Test
    fun `encodings are listed shallow to deep without repeats`() {
        val codes = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3)
        assertThat(OutputMapping.encodings(codes)).containsExactly(
            OutputEncoding.PCM_16,
            OutputEncoding.PCM_24_PACKED,
            OutputEncoding.PCM_FLOAT,
            OutputEncoding.OTHER,
        ).inOrder()
    }
}
