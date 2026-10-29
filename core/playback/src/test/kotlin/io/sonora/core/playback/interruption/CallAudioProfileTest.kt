package io.sonora.core.playback.interruption

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CallAudioProfileTest {

    @Test
    fun `call and communication modes hold the call profile`() {
        listOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
            AudioManager.MODE_ASSISTANT_CONVERSATION,
        ).forEach { mode ->
            assertThat(CallAudioProfile.isActive(mode, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).isTrue()
        }
    }

    @Test
    fun `normal and ringtone modes on a media route do not`() {
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_NORMAL, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)).isFalse()
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_RINGTONE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).isFalse()
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_NORMAL, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)).isFalse()
        // LE Audio earbuds play media as a BLE headset too.
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_NORMAL, AudioDeviceInfo.TYPE_BLE_HEADSET)).isFalse()
    }

    @Test
    fun `media routed to a call device holds it without a mode change`() {
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_NORMAL, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isTrue()
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_NORMAL, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)).isTrue()
    }

    @Test
    fun `an unknown route goes by the mode`() {
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_NORMAL, null)).isFalse()
        assertThat(CallAudioProfile.isActive(AudioManager.MODE_IN_COMMUNICATION, null)).isTrue()
    }
}
