package io.sonora.core.playback.output

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import io.sonora.core.domain.playback.output.AudioOutputMonitor
import io.sonora.core.domain.playback.output.BitPerfectPlan
import io.sonora.core.domain.playback.output.BitPerfectPlanner
import io.sonora.core.domain.playback.output.PcmConverter
import io.sonora.core.domain.playback.output.StreamFormat

/** The mixer format chosen for a stream and the USB device it is for. */
internal data class BitPerfectChoice(val deviceId: Int, val plan: BitPerfectPlan.Active)

/**
 * The player's bit-perfect output: whether it is [armed] (the playback service sets it from the user's switch and the
 * connected device), the format of the stream being configured, and the choice of mixer format for that stream. The
 * audio output provider asks for the choice when the sink is configured and applies it when the AudioTrack opens.
 */
@UnstableApi
internal class BitPerfectOutput(
    private val outputs: AudioOutputMonitor,
    private val mixer: MixerAttributesApplier,
    private val log: (String) -> Unit = { Log.i(AUDIO_OUT_TAG, it) },
) : BitPerfectGate {

    @Volatile
    override var armed: Boolean = false

    @Volatile
    private var stream: StreamFormat? = null

    override fun streamStarting(format: Format) {
        stream = StreamFormats.of(format)
    }

    /** The mixer format for the stream being configured, or `null` when it plays the normal way (and why is logged). */
    fun choose(sampleRate: Int, channelCount: Int): BitPerfectChoice? {
        if (!armed) return null
        val source = stream?.takeIf { it.sampleRate == sampleRate && it.channelCount == channelCount }
        if (source == null) {
            log("Bit-perfect: stream format unknown for ${sampleRate}Hz/${channelCount}ch; playing the normal way")
            return null
        }
        val device = outputs.current().primary
        if (device == null) {
            log("Bit-perfect: no output device; playing the normal way")
            return null
        }
        return when (val plan = BitPerfectPlanner.plan(source, device)) {
            is BitPerfectPlan.Unavailable -> {
                log("Bit-perfect unavailable for ${source.sampleRate}Hz/${source.channelCount}ch ${source.pcm}: ${plan.reason}; playing the normal way")
                null
            }
            is BitPerfectPlan.Active -> if (PcmConverter.supports(plan.sinkEncoding, plan.mixer.encoding)) {
                log("Bit-perfect plan for ${source.sampleRate}Hz/${source.channelCount}ch ${source.pcm}: ${plan.mixer.describe()}" +
                    if (plan.needsConversion) " (sink ${plan.sinkEncoding} widened to ${plan.mixer.encoding})" else "")
                BitPerfectChoice(device.id, plan)
            } else {
                log("Bit-perfect: no lossless conversion from ${plan.sinkEncoding} to ${plan.mixer.encoding}; playing the normal way")
                null
            }
        }
    }

    /** Asks Android to mix in [choice]'s format; whether it accepted. A refusal is logged and the track still plays. */
    fun apply(choice: BitPerfectChoice): Boolean {
        val accepted = mixer.apply(choice.deviceId, choice.plan.mixer)
        log(if (accepted) "Bit-perfect mixer format set: ${choice.plan.mixer.describe()}" else "Bit-perfect: Android did not accept ${choice.plan.mixer.describe()}")
        return accepted
    }

    /** Back to Android's normal mixing. */
    fun release() = mixer.clear()
}
