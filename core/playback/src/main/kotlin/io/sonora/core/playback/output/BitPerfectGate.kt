package io.sonora.core.playback.output

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * What the audio sink asks of bit-perfect output: whether it is [armed] (every effect then stays off and the stream is
 * handed on exactly as it is) and a word about each stream's own format before its sink is configured, which the output
 * needs to choose the mixer format (the sink below only ever sees float or 16-bit PCM).
 */
@UnstableApi
interface BitPerfectGate {
    val armed: Boolean

    /** The format of the stream about to be configured, as the decoder or extractor delivers it. */
    fun streamStarting(format: Format)
}
