package io.sonora.core.playback.dub

import io.sonora.core.djloop.PcmFormat
import io.sonora.core.dubmodel.DubBypass
import io.sonora.core.playback.loop.LoopOff
import io.sonora.core.playback.loop.StreamHosting

/** What the DUB sink does with a stream: run the stage on [Hosted], or pass it through untouched. */
internal sealed interface DubHostingDecision {
    data class Hosted(val format: PcmFormat) : DubHostingDecision

    /** Untouched: DUB mode is off ([reason] null), or it is on and [reason] says why the stage cannot run. */
    data class Bypassed(val reason: DubBypass?) : DubHostingDecision
}

/** The rules of [DubAudioSink] for hosting the DUB stage (design §3.5), apart from the sink. */
internal object DubHosting {

    private val off = DubHostingDecision.Bypassed(null)
    private val bitPerfect = DubHostingDecision.Bypassed(DubBypass.BIT_PERFECT)
    private val remote = DubHostingDecision.Bypassed(DubBypass.REMOTE_RECEIVER)
    private val channels = DubHostingDecision.Bypassed(DubBypass.TOO_MANY_CHANNELS)
    private val format = DubHostingDecision.Bypassed(DubBypass.UNSUPPORTED_FORMAT)

    /**
     * DUB mode first, then bit-perfect output and a remote receiver, then the stream ([LoopHosting.ofStream]'s reading
     * of it): more than [MAX_CHANNELS] channels, or anything that is not plain PCM, bypasses the stage.
     */
    fun decide(enabled: Boolean, bitPerfectArmed: Boolean, remoteRouted: Boolean, stream: StreamHosting): DubHostingDecision =
        when {
            !enabled -> off
            bitPerfectArmed -> bitPerfect
            remoteRouted -> remote
            stream is StreamHosting.Hostable ->
                if (stream.format.channelCount > MAX_CHANNELS) channels else DubHostingDecision.Hosted(stream.format)
            stream is StreamHosting.Unhostable && stream.reason == LoopOff.NO_STREAM -> off
            else -> format
        }

    const val MAX_CHANNELS = 2
}
