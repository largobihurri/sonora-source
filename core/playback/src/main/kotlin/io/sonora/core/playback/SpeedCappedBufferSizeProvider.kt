package io.sonora.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider
import kotlin.math.min

/**
 * Sizes the AudioTrack as [delegate] does, but for a playback speed of at most [maxSpeed].
 *
 * With output playback parameters on, `DefaultAudioSink` asks for a buffer able to hold the fastest speed it allows
 * (8x), about 4 s of audio at 1x. The app only reaches 2x ([io.sonora.core.domain.playback.PlaybackTempo]), so the rest
 * is dead queue: audio decoded seconds ahead of the speaker, late to react to anything.
 */
@UnstableApi
internal class SpeedCappedBufferSizeProvider(
    private val delegate: AudioTrackBufferSizeProvider,
    private val maxSpeed: Double,
) : AudioTrackBufferSizeProvider {

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int = delegate.getBufferSizeInBytes(
        minBufferSizeInBytes,
        encoding,
        outputMode,
        pcmFrameSize,
        sampleRate,
        bitrate,
        min(maxAudioTrackPlaybackSpeed, maxSpeed),
    )
}
