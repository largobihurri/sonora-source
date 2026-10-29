package io.sonora.core.domain.playback.output

/** The PCM sample type of the file being played. */
enum class SourcePcm(
    /** What Media3's audio sink hands to the audio output for this source: 16-bit stays 16-bit, deeper PCM becomes float. */
    val sinkEncoding: OutputEncoding,
) {
    INT8(OutputEncoding.PCM_16),
    INT16(OutputEncoding.PCM_16),
    INT24(OutputEncoding.PCM_FLOAT),
    INT32(OutputEncoding.PCM_FLOAT),
    FLOAT32(OutputEncoding.PCM_FLOAT),
}

/** The format of the stream about to be played. */
data class StreamFormat(val sampleRate: Int, val channelCount: Int, val pcm: SourcePcm)

/** Why bit-perfect output cannot be used for a stream; the player then plays the normal way. */
enum class BitPerfectUnavailable {
    NOT_USB,
    NO_BIT_PERFECT_FORMATS,

    /** 32-bit integer PCM cannot cross the sink's float stage without losing its low bits. */
    SOURCE_NOT_SUPPORTED,
    SAMPLE_RATE_NOT_OFFERED,
    CHANNELS_NOT_OFFERED,

    /** The device's bit-perfect formats are all narrower than the source, or the wrong sample type. */
    DEPTH_NOT_OFFERED,
}

sealed interface BitPerfectPlan {
    /**
     * Play on [mixer]'s format. The audio sink hands the output [sinkEncoding] PCM, which the output widens to the
     * mixer's encoding when they differ ([needsConversion]); every step keeps each sample's value exactly.
     */
    data class Active(val mixer: MixerOption, val sinkEncoding: OutputEncoding) : BitPerfectPlan {
        val needsConversion: Boolean get() = sinkEncoding != mixer.encoding
    }

    data class Unavailable(val reason: BitPerfectUnavailable) : BitPerfectPlan
}

/**
 * Chooses the mixer format a stream plays on so the device receives its samples unchanged: the same sample rate and
 * channel count, and the narrowest encoding that holds every sample exactly. Widening an integer sample (16 to 24 or
 * 32 bits) or turning a 24-bit one into float is lossless; narrowing is not, so it is never chosen.
 */
object BitPerfectPlanner {
    fun plan(stream: StreamFormat, device: AudioOutputDevice): BitPerfectPlan {
        if (device.kind != OutputDeviceKind.USB) return BitPerfectPlan.Unavailable(BitPerfectUnavailable.NOT_USB)
        if (stream.pcm == SourcePcm.INT32) return BitPerfectPlan.Unavailable(BitPerfectUnavailable.SOURCE_NOT_SUPPORTED)
        val offered = device.bitPerfectOptions
        if (offered.isEmpty()) return BitPerfectPlan.Unavailable(BitPerfectUnavailable.NO_BIT_PERFECT_FORMATS)
        val sameRate = offered.filter { it.sampleRate == stream.sampleRate }
        if (sameRate.isEmpty()) return BitPerfectPlan.Unavailable(BitPerfectUnavailable.SAMPLE_RATE_NOT_OFFERED)
        val sameChannels = sameRate.filter { it.channelCount == stream.channelCount }
        if (sameChannels.isEmpty()) return BitPerfectPlan.Unavailable(BitPerfectUnavailable.CHANNELS_NOT_OFFERED)
        val chosen = acceptable(stream.pcm).firstNotNullOfOrNull { encoding -> sameChannels.firstOrNull { it.encoding == encoding } }
            ?: return BitPerfectPlan.Unavailable(BitPerfectUnavailable.DEPTH_NOT_OFFERED)
        return BitPerfectPlan.Active(chosen, stream.pcm.sinkEncoding)
    }

    /** The mixer encodings that hold [pcm] exactly, best first. */
    private fun acceptable(pcm: SourcePcm): List<OutputEncoding> = when (pcm) {
        SourcePcm.INT8, SourcePcm.INT16 ->
            listOf(OutputEncoding.PCM_16, OutputEncoding.PCM_24_PACKED, OutputEncoding.PCM_32, OutputEncoding.PCM_FLOAT)
        SourcePcm.INT24 -> listOf(OutputEncoding.PCM_24_PACKED, OutputEncoding.PCM_32, OutputEncoding.PCM_FLOAT)
        SourcePcm.FLOAT32 -> listOf(OutputEncoding.PCM_FLOAT)
        SourcePcm.INT32 -> emptyList()
    }
}

/** Whether bit-perfect output is armed: switched on by the user while a USB device that offers it is the one heard. */
object BitPerfectMode {
    fun armed(enabled: Boolean, outputs: AudioOutputSnapshot): Boolean =
        enabled && outputs.bitPerfect == BitPerfectAvailability.AVAILABLE
}
