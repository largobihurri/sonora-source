package io.sonora.core.playback.loop

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider

/**
 * The AudioTrack's buffer while DJ mode is on: about [bufferMs] of PCM instead of [delegate]'s sizing (hundreds of ms
 * for the app's top speed). The buffer is the delay between the decoder and the speaker, and so how late a Loop Out
 * pressed now engages (the loop starts at the decoder's frontier, which is that far ahead of what is heard), how late a
 * tempo or seek change is heard, and how far ahead the audio in the sink is of the position the DJ screen shows.
 *
 * The price is slack: the renderer wakes about every half buffer (62 ms at 1x with 125 ms, 31 ms at 2x speed, where the
 * buffer holds half the time) and must refill before the speaker drains it, so a playback thread held up for longer than
 * that (a long garbage collection, a busy core) underruns audibly. That is why [bufferMs] is a named figure to tune on
 * the phone, and why the buffer is only this small while DJ mode is on. Streams that are not PCM, and a buffer the
 * platform would not allow ([minBufferSizeInBytes] is the floor), keep [delegate]'s size.
 *
 * The size is read when an AudioTrack is created, so turning DJ mode on or off takes effect from the next track or seek.
 */
@UnstableApi
internal class DjAudioTrackBufferSizeProvider(
    private val delegate: AudioTrackBufferSizeProvider,
    private val djMode: () -> Boolean,
    private val bufferMs: Int = DJ_BUFFER_MS,
) : AudioTrackBufferSizeProvider {

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int {
        val normal = delegate.getBufferSizeInBytes(
            minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxAudioTrackPlaybackSpeed,
        )
        if (!djMode() || outputMode != DefaultAudioSink.OUTPUT_MODE_PCM || pcmFrameSize <= 0 || sampleRate <= 0) return normal
        val frames = sampleRate.toLong() * bufferMs / MS_PER_SECOND
        val wanted = (frames * pcmFrameSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Never above the usual size (it already is small for a short track format) and never under the platform's minimum.
        val aligned = wanted - wanted % pcmFrameSize
        return aligned.coerceAtLeast(minBufferSizeInBytes - minBufferSizeInBytes % pcmFrameSize).coerceAtMost(normal)
    }

    companion object {
        /**
         * The DJ mode buffer, ms of audio at 1x. 125 ms: a Loop Out engages within about that of the press, and the
         * renderer wakes every ~60 ms. Smaller risks underruns on a busy phone; larger makes loops later.
         */
        const val DJ_BUFFER_MS: Int = 125

        private const val MS_PER_SECOND = 1_000L
    }
}
