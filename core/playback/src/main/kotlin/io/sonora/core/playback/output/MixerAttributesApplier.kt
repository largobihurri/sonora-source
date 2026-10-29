package io.sonora.core.playback.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.sonora.core.domain.playback.output.MixerOption

/** Sets and clears the mixer format Android uses for a USB device (Android 14's preferred mixer attributes). */
internal interface MixerAttributesApplier {
    /** Makes [option] the preferred bit-perfect mixer format of device [deviceId]; whether Android accepted it. */
    fun apply(deviceId: Int, option: MixerOption): Boolean

    /** Gives back whatever [apply] set, so Android mixes the normal way again. Does nothing when nothing is set. */
    fun clear()
}

/** [MixerAttributesApplier] over [AudioManager]; below Android 14 it accepts nothing. */
internal class AudioManagerMixerAttributes(context: Context) : MixerAttributesApplier {

    private val audio = context.getSystemService(AudioManager::class.java)
    private var applied: Pair<Int, MixerOption>? = null

    @Synchronized
    override fun apply(deviceId: Int, option: MixerOption): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (applied == deviceId to option) return true
        return applyOnApi34(deviceId, option)
    }

    @Synchronized
    override fun clear() {
        val (deviceId, _) = applied ?: return
        applied = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        clearOnApi34(deviceId)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyOnApi34(deviceId: Int, option: MixerOption): Boolean {
        val device = output(deviceId) ?: return false
        val attributes = try {
            audio.getSupportedMixerAttributes(device).firstOrNull { matches(it, option) }
        } catch (e: RuntimeException) {
            Log.w(AUDIO_OUT_TAG, "Couldn't list the mixer attributes of device $deviceId", e)
            null
        } ?: return false
        val accepted = try {
            audio.setPreferredMixerAttributes(MEDIA, device, attributes)
        } catch (e: RuntimeException) {
            // IllegalArgumentException (not a USB output, unsupported usage) and SecurityException (no permission).
            Log.w(AUDIO_OUT_TAG, "Android refused the mixer attributes ${option.describe()}", e)
            false
        }
        if (accepted) applied = deviceId to option
        return accepted
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearOnApi34(deviceId: Int) {
        val device = output(deviceId) ?: return
        try {
            audio.clearPreferredMixerAttributes(MEDIA, device)
        } catch (e: RuntimeException) {
            Log.w(AUDIO_OUT_TAG, "Couldn't clear the preferred mixer attributes of device $deviceId", e)
        }
    }

    private fun output(deviceId: Int): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun matches(attributes: AudioMixerAttributes, option: MixerOption) =
        attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            attributes.format.sampleRate == option.sampleRate &&
            attributes.format.channelCount == option.channelCount &&
            OutputMapping.encoding(attributes.format.encoding) == option.encoding

    private companion object {
        val MEDIA: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    }
}
