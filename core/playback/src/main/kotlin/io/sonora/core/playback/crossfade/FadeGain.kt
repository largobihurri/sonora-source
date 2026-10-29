package io.sonora.core.playback.crossfade

/**
 * The main player's crossfade level (linear 0..1), set on the main thread and applied by the player's audio sink on
 * the playback thread with its next buffer (see [io.sonora.core.playback.equalizer.EqualizerAudioSink]). It sits on
 * top of the player's own volume and never shows in it, so controllers never see the fades.
 */
class FadeGain {
    @Volatile
    var gain: Float = 1f
}
