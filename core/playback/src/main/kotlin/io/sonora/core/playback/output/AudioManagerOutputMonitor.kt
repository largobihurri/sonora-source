package io.sonora.core.playback.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.playback.output.AudioOutputDevice
import io.sonora.core.domain.playback.output.AudioOutputMonitor
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.MixerOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Logcat tag of everything about the audio outputs (`adb logcat -s SonoraAudioOut`). */
internal const val AUDIO_OUT_TAG = "SonoraAudioOut"

/**
 * [AudioOutputMonitor] over [AudioManager]: the output devices with the rates, encodings and channel counts they
 * report, which of them media is routed to (Android 13+) and, on Android 14+, the mixer attributes each one offers.
 */
@Singleton
class AudioManagerOutputMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : AudioOutputMonitor {

    private val audio = context.getSystemService(AudioManager::class.java)

    override fun current(): AudioOutputSnapshot = read()

    override val snapshots: Flow<AudioOutputSnapshot> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(read())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(read())
            }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        trySend(read())
        awaitClose { audio.unregisterAudioDeviceCallback(callback) }
    }.distinctUntilChanged()

    private fun read(): AudioOutputSnapshot {
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }.map(::describe)
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) routedIds() else emptySet()
        return AudioOutputSnapshot(devices, routed, mixerApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun routedIds(): Set<Int> =
        runCatching { audio.getAudioDevicesForAttributes(MEDIA).map { it.id }.toSet() }.getOrDefault(emptySet())

    private fun describe(device: AudioDeviceInfo): AudioOutputDevice {
        val mixer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) mixerOptions(device) else emptyList()
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) activeMixer(device) else null
        return AudioOutputDevice(
            id = device.id,
            kind = OutputMapping.kind(device.type),
            productName = device.productName?.toString().orEmpty(),
            sampleRates = OutputMapping.sortedUnique(device.sampleRates),
            encodings = OutputMapping.encodings(device.encodings),
            channelCounts = OutputMapping.sortedUnique(device.channelCounts),
            mixerOptions = mixer,
            activeMixer = active,
            purpose = OutputMapping.purpose(device.type),
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun mixerOptions(device: AudioDeviceInfo): List<MixerOption> = try {
        audio.getSupportedMixerAttributes(device).map(::option).distinct()
    } catch (e: RuntimeException) {
        Log.w(AUDIO_OUT_TAG, "Couldn't list the mixer attributes of ${device.productName}", e)
        emptyList()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun activeMixer(device: AudioDeviceInfo): MixerOption? = try {
        audio.getPreferredMixerAttributes(MEDIA, device)?.let(::option)
    } catch (e: RuntimeException) {
        Log.w(AUDIO_OUT_TAG, "Couldn't read the preferred mixer attributes of ${device.productName}", e)
        null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun option(attributes: AudioMixerAttributes) = MixerOption(
        sampleRate = attributes.format.sampleRate,
        channelCount = attributes.format.channelCount,
        encoding = OutputMapping.encoding(attributes.format.encoding),
        bitPerfect = attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
    )

    private companion object {
        val MEDIA: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    }
}
