package io.sonora.core.domain.playback.output

/** One-line descriptions of output devices for logcat (tag `SonoraAudioOut`), so a DAC can be checked without a screen. */
object AudioOutputLog {
    fun describe(device: AudioOutputDevice): String =
        "${device.kind.logName} id=${device.id} \"${device.productName}\" rates=${device.sampleRates} " +
            "encodings=${device.encodings.map { it.name }} channels=${device.channelCounts} " +
            "mixer=${device.mixerOptions.map { it.describe() }} bitPerfect=${device.offersBitPerfect}"
}
