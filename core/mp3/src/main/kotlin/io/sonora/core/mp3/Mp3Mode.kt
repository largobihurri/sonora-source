package io.sonora.core.mp3

/** How LAME spends bits on the stream. */
sealed interface Mp3Mode {

    /**
     * Constant bitrate. [bitrateKbps] must be one of [Mp3Encoder.cbrBitratesKbps] for the encoder's sample rate,
     * which [Mp3Encoder.open] checks.
     */
    data class Cbr(val bitrateKbps: Int) : Mp3Mode

    /** LAME's variable bitrate (`lame -V`): 0 is the best quality and the largest file, 9 the smallest. */
    data class Vbr(val quality: Int) : Mp3Mode {
        init {
            require(quality in 0..9) { "VBR quality must be 0..9, not $quality" }
        }
    }
}
