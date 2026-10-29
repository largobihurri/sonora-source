package io.sonora.core.playback.output

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import io.sonora.core.domain.playback.output.OutputEncoding
import io.sonora.core.domain.playback.output.PcmConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps Media3's AudioTrack output so that, while [bitPerfect] is armed and a stream has a lossless mixer format, the
 * AudioTrack opens in exactly that format (sample rate, channels and encoding of the USB device's bit-perfect mixer
 * option), Android is asked to mix in it just before the track opens, and no playback parameters are used. Media3's
 * sink only produces 16-bit or float PCM, so a mixer encoding the sink does not produce (24-bit packed, 32-bit) is
 * reached by [ConvertingAudioOutput], which widens each sample losslessly. Everything else goes straight to [delegate],
 * and a track that is not bit-perfect gives Android back its normal mixing first. (Behind this wrapper the sink reports no
 * audio capabilities of its own, which only the passthrough of encoded audio uses; Sonora plays PCM only.)
 */
@UnstableApi
internal class BitPerfectAudioOutputProvider(
    delegate: AudioOutputProvider,
    private val bitPerfect: BitPerfectOutput,
) : ForwardingAudioOutputProvider(delegate) {

    /** The choice made for each configuration handed out, until its track opens (the sink opens it right after). */
    private val choices = HashMap<OutputConfig, BitPerfectChoice>()

    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        val choice = bitPerfect.choose(formatConfig.format.sampleRate, formatConfig.format.channelCount)
            ?.takeIf { !formatConfig.enableOffload && !formatConfig.enableTunneling }
            ?: return super.getOutputConfig(formatConfig)
        val asked = FormatConfig.Builder(
            formatConfig.format.buildUpon().setPcmEncoding(StreamFormats.media3Encoding(choice.plan.mixer.encoding)).build(),
        )
            .setAudioAttributes(formatConfig.audioAttributes)
            .setPreferredDevice(formatConfig.preferredDevice)
            .setEnableHighResolutionPcmOutput(formatConfig.enableHighResolutionPcmOutput)
            .setEnablePlaybackParameters(false)
            .setEnableOffload(false)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .setEnableTunneling(false)
            .setPreferredBufferSize(formatConfig.preferredBufferSize)
            .build()
        val config = super.getOutputConfig(asked)
        if (choices.size > MAX_PENDING) choices.clear()
        choices[config] = choice
        return config
    }

    override fun getAudioOutput(config: OutputConfig): AudioOutput {
        val choice = choices.remove(config)
        if (choice == null) {
            // A normal track: Android must not keep mixing in a format an earlier bit-perfect track asked for.
            bitPerfect.release()
            return super.getAudioOutput(config)
        }
        bitPerfect.apply(choice)
        val output = super.getAudioOutput(config)
        val plan = choice.plan
        return if (plan.needsConversion) {
            ConvertingAudioOutput(output, plan.sinkEncoding, plan.mixer.encoding, plan.mixer.channelCount)
        } else {
            output
        }
    }

    override fun release() {
        bitPerfect.release()
        super.release()
    }

    private companion object {
        const val MAX_PENDING = 8
    }
}

/**
 * An [AudioOutput] whose AudioTrack is in [to] while the sink above writes [from] (16-bit or float PCM): each write
 * widens up to [CHUNK_FRAMES] frames losslessly ([PcmConverter]) and passes them on, and the buffer it was given only
 * advances by the frames the track took, so the sink's own accounting stays exact.
 */
@UnstableApi
internal class ConvertingAudioOutput(
    private val delegate: AudioOutput,
    private val from: OutputEncoding,
    private val to: OutputEncoding,
    private val channels: Int,
) : AudioOutput by delegate {

    private val inFrame = PcmConverter.bytesPerSample(from) * channels
    private val outFrame = PcmConverter.bytesPerSample(to) * channels
    private val scratch: ByteBuffer = ByteBuffer.allocateDirect(CHUNK_FRAMES * outFrame).order(ByteOrder.nativeOrder())

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        while (buffer.remaining() >= inFrame) {
            val start = buffer.position()
            val frames = minOf(buffer.remaining() / inFrame, CHUNK_FRAMES)
            scratch.clear()
            PcmConverter.convert(buffer, from, to, channels, frames, scratch)
            scratch.flip()
            val offered = scratch.remaining()
            if (!delegate.write(scratch, encodedAccessUnitCount, presentationTimeUs)) {
                // Only the frames the track took count as consumed; the rest is converted again on the next call.
                val taken = (offered - scratch.remaining()) / outFrame
                buffer.position(start + taken * inFrame)
                return false
            }
        }
        // A partial frame at the end (never produced by Media3) cannot be played: consume it.
        buffer.position(buffer.limit())
        return true
    }

    override fun canReuseAudioOutput(currentConfig: OutputConfig, newFormat: FormatConfig, newConfig: OutputConfig): Boolean =
        delegate.canReuseAudioOutput(currentConfig, newFormat, newConfig)

    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId) = delegate.setPlayerId(playerId)

    private companion object {
        const val CHUNK_FRAMES = 2_048
    }
}
