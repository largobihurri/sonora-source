package io.sonora.core.playback.loop

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.sonora.core.djloop.PcmEncoding
import io.sonora.core.djloop.PcmFormat

/** Why loops are not hosted on a stream: the answer the DJ screen's dimmed buttons never gave. */
internal enum class LoopOff {
    /** DJ mode is off. */
    DJ_MODE_OFF,

    /** Bit-perfect USB output is armed: like the tempo, loops are held off there. */
    BIT_PERFECT_ARMED,

    /** Nothing has been configured yet. */
    NO_STREAM,

    /** The sink was handed something that is not raw PCM (a compressed or passthrough stream). */
    NOT_RAW_PCM,

    /** The format reports no sample rate or no channels. */
    NO_RATE_OR_CHANNELS,

    /** The renderer remaps the channels (some Opus and Vorbis decoders, 6-channel workarounds). */
    CHANNEL_MAPPING,

    /** A PCM encoding the engine does not hold (8-bit, big endian float, double). */
    PCM_ENCODING,
}

/** Whether the engine can host a stream, judged on the stream alone. */
internal sealed interface StreamHosting {
    data class Hostable(val format: PcmFormat) : StreamHosting {
        internal val decision = LoopHostingDecision.Host(format)
    }

    data class Unhostable(val reason: LoopOff, val detail: String) : StreamHosting {
        internal val decision = LoopHostingDecision.PassThrough(reason, detail)
    }

    companion object {
        /** No stream yet. */
        val NONE: StreamHosting = Unhostable(LoopOff.NO_STREAM, "no stream configured")
    }
}

/** What the sink does with the stream now: run the engine on [Host], or pass the audio through and say why. */
internal sealed interface LoopHostingDecision {
    data class Host(val format: PcmFormat) : LoopHostingDecision

    data class PassThrough(val reason: LoopOff, val detail: String) : LoopHostingDecision
}

/** The rules of [LoopAudioSink] for hosting loops, apart from the sink: DJ mode, bit-perfect output and the stream itself. */
internal object LoopHosting {

    /** Whether the engine can host a stream of [format] given the renderer's channel mapping ([hasChannelMapping]). */
    @OptIn(UnstableApi::class) // the big endian PCM constants
    fun ofStream(format: Format, hasChannelMapping: Boolean): StreamHosting {
        val description = describe(format, hasChannelMapping)
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW) return StreamHosting.Unhostable(LoopOff.NOT_RAW_PCM, description)
        if (format.sampleRate <= 0 || format.channelCount <= 0) return StreamHosting.Unhostable(LoopOff.NO_RATE_OR_CHANNELS, description)
        if (hasChannelMapping) return StreamHosting.Unhostable(LoopOff.CHANNEL_MAPPING, description)
        val encoding = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16
            C.ENCODING_PCM_24BIT -> PcmEncoding.PCM_24
            C.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32
            C.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT_32
            // AIFF: Media3 decodes it to big endian PCM, and this sink sits before the one that converts it.
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> PcmEncoding.PCM_16_BE
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> PcmEncoding.PCM_24_BE
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> PcmEncoding.PCM_32_BE
            else -> return StreamHosting.Unhostable(LoopOff.PCM_ENCODING, description)
        }
        return StreamHosting.Hostable(PcmFormat(format.sampleRate, format.channelCount, encoding))
    }

    private val djModeOff = LoopHostingDecision.PassThrough(LoopOff.DJ_MODE_OFF, "DJ mode is off")
    private val bitPerfect = LoopHostingDecision.PassThrough(LoopOff.BIT_PERFECT_ARMED, "bit-perfect output is armed")

    /**
     * What to do now: DJ mode and bit-perfect output are asked first, the stream last. Called on the audio thread for
     * every buffer, so it allocates nothing.
     */
    fun decide(stream: StreamHosting, djMode: Boolean, bitPerfectArmed: Boolean): LoopHostingDecision = when {
        !djMode -> djModeOff
        bitPerfectArmed -> bitPerfect
        else -> when (stream) {
            is StreamHosting.Unhostable -> stream.decision
            is StreamHosting.Hostable -> stream.decision
        }
    }

    private fun describe(format: Format, hasChannelMapping: Boolean) =
        "mime=${format.sampleMimeType} rate=${format.sampleRate} channels=${format.channelCount} " +
            "pcmEncoding=${format.pcmEncoding} channelMapping=$hasChannelMapping"
}
