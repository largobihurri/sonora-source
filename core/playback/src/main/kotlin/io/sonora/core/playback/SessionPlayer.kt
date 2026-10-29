package io.sonora.core.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackBufferSizeProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.core.playback.audio.SonoraExtractorsFactory
import io.sonora.core.playback.crossfade.FadeGain
import io.sonora.core.playback.dub.DubAudioSink
import io.sonora.core.playback.dub.DubHub
import io.sonora.core.playback.equalizer.EqualizerAudioProcessor
import io.sonora.core.playback.equalizer.EqualizerAudioSink
import io.sonora.core.playback.levels.BandLevelMeter
import io.sonora.core.playback.levels.LiveBandLevels
import io.sonora.core.playback.loop.DjAudioTrackBufferSizeProvider
import io.sonora.core.playback.loop.LoopAudioSink
import io.sonora.core.playback.loop.LoopHub
import io.sonora.core.playback.output.BitPerfectAudioOutputProvider
import io.sonora.core.playback.output.BitPerfectOutput

/**
 * The session's player, shared by [PlaybackService] and the session tests so both run the same configuration.
 *
 * Its shuffle order is the identity ([ShuffleOrder.UnshuffledShuffleOrder] keeps itself unshuffled through every
 * playlist edit), so Media3's shuffle flag never reorders playback: the flag only mirrors the app's shuffle state,
 * which shuffles the real playlist instead. Every controller (the app, the notification, Bluetooth, Android Auto)
 * therefore sees the same flag and the same play order, and a toggle from any of them reaches the app's queue.
 *
 * Audio: AIFF / AIFF-C is read by [io.sonora.core.playback.audio.AiffExtractor] (Media3 has none), ahead of the
 * default extractors. Float output is on, so 24-bit, 32-bit and float PCM (WAV or AIFF) reach the AudioTrack as
 * 32-bit float instead of being cut to 16-bit; 8/16-bit sources keep a 16-bit track. The sample rate is never
 * forced: the track opens at the source's rate and Android resamples only if the output needs it.
 *
 * Speed and pitch ([io.sonora.core.domain.playback.PlaybackTempo]) are applied by the AudioTrack's own playback params,
 * for every stream: float output bypasses Media3's speed processor (Sonic) for high-resolution PCM, and with the
 * equalizer on every stream is float, so that processor would drop them. Silence skipping is not used.
 * Because of that, Sonic (16-bit PCM only) is never configured with a non-neutral speed or pitch: it stays inactive for
 * the life of the sink, so a speed change can neither cut a high-resolution stream to 16-bit nor leave the sink
 * there after returning to neutral (guarded by `SessionPlayerAudioTest`). Do not turn output playback parameters off.
 *
 * The equalizer ([EqualizerAudioProcessor]) runs in front of the audio sink ([EqualizerAudioSink]) on every PCM
 * stream, following [equalizer]'s settings live. It is playback only: conversions never go through this player.
 * ReplayGain volume normalisation runs there too, as a gain stage ahead of the filters, following [replayGain]'s
 * settings live.
 *
 * The band level meter ([BandLevelMeter], feeding [levels]) listens at the same place, to the PCM the AudioTrack is
 * given, always (not only while someone watches, so queued audio already has levels when the bars appear). Every local
 * file reaches the sink as PCM (Media3's offload is never turned on), so the meter hears all of them; only an encoded
 * passthrough stream (none today) would leave it without levels.
 *
 * Bit-perfect USB output ([BitPerfectOutput], armed by the playback service): the sink hands streams on untouched and
 * the AudioTrack opens in the USB device's own bit-perfect mixer format (see [BitPerfectAudioOutputProvider]).
 *
 * The AudioTrack's buffer is sized for at most the app's own top speed ([SpeedCappedBufferSizeProvider]). With output
 * playback parameters on, Media3 would size it for its 8x ceiling: seconds of audio queued ahead of the speaker, which
 * delays every speed, pitch and seek change and deepens what the level meter has to keep ahead of the played position.
 *
 * DJ loops ([loops], the session's player only): while DJ mode is on ([LoopHub.enabled]) a [LoopAudioSink] in front of the
 * equalizer repeats sample-accurate loops, and the AudioTrack's buffer is kept to about 125 ms
 * ([DjAudioTrackBufferSizeProvider]) so a loop engages soon after its Out. Both are inert while DJ mode is off, and the
 * crossfade tail player is built without them.
 *
 * DUB mode ([dub], the session's player only): a [DubAudioSink] between the loop sink and the equalizer runs the DUB
 * stage (echo, filter, five-way preamp, limiter) on streams configured while DUB mode is on, as float; the AudioTrack
 * keeps the DJ buffer meanwhile, and DUB mode keeps the loop hub on too (see `LoopController`). With DUB mode off the
 * stage hands every stream on untouched.
 *
 * Crossfade: the session's player applies [fadeGain] (when given) at its sink, on top of its own volume. The tail
 * player of a crossfade ([io.sonora.core.playback.crossfade.CrossfadeController]) is built here too, so it plays
 * through the same equalizer, but without [handleAudioFocus]: it must never take focus from the session's player, and
 * it stops whenever that player pauses (focus loss, headphones unplugged) instead.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object SessionPlayer {
    fun build(
        context: Context,
        levels: LiveBandLevels? = null,
        replayGain: () -> ReplayGainSettings = { ReplayGainSettings() },
        fadeGain: FadeGain? = null,
        handleAudioFocus: Boolean = true,
        bitPerfect: BitPerfectOutput? = null,
        loops: LoopHub? = null,
        dub: DubHub? = null,
        equalizer: () -> EqualizerSettings = { EqualizerSettings() },
    ): ExoPlayer =
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                SonoraRenderersFactory(context, equalizer, replayGain, levels, fadeGain, bitPerfect, loops, dub)
                    .setEnableAudioFloatOutput(true)
                    .setEnableAudioOutputPlaybackParameters(true),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, SonoraExtractorsFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus,
            )
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply { setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0)) }
}

/** Media3's renderers, with ReplayGain, the equalizer (and the level meter) in front of the default audio sink. */
@UnstableApi
private class SonoraRenderersFactory(
    context: Context,
    private val equalizer: () -> EqualizerSettings,
    private val replayGain: () -> ReplayGainSettings,
    private val levels: LiveBandLevels?,
    private val fadeGain: FadeGain?,
    private val bitPerfect: BitPerfectOutput?,
    private val loops: LoopHub?,
    private val dub: DubHub?,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? {
        // The default sink (Media3 1.11.1: float output and output playback parameters as asked, nothing else), with the
        // AudioTrack buffer sized for the app's own top speed instead of Media3's 8x. Its output is Media3's AudioTrack one
        // (what the sink builds itself when given none), wrapped for bit-perfect USB output when that is wired in.
        val speedCapped = SpeedCappedBufferSizeProvider(AudioTrackBufferSizeProvider.DEFAULT, PlaybackTempo.MAX_SPEED)
        val tracks = AudioTrackAudioOutputProvider.Builder(context)
            .setAudioTrackBufferSizeProvider(
                if (loops != null || dub != null) {
                    DjAudioTrackBufferSizeProvider(speedCapped, { loops?.enabled == true || dub?.enabled == true })
                } else {
                    speedCapped
                },
            )
            .build()
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioOutputProvider(if (bitPerfect != null) BitPerfectAudioOutputProvider(tracks, bitPerfect) else tracks)
            .build()
        val equalized = EqualizerAudioSink(sink, EqualizerAudioProcessor(equalizer), equalizer, levels?.let(::BandLevelMeter), replayGain, fadeGain, bitPerfect)
        // The DUB stage goes in front of the equalizer too, so the band meter and the EQ hear its effects.
        val dubbed = if (dub != null) DubAudioSink(equalized, dub, bitPerfect) else equalized
        // Loops go in front of both: a loop is in the decoder's frames, and the stages then hear it as any audio.
        return if (loops != null) LoopAudioSink(dubbed, loops, bitPerfect) else dubbed
    }
}
