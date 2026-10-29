package io.sonora.core.playback.equalizer

import io.sonora.core.domain.equalizer.EqualizerSettings

/**
 * The equalizer settings the player follows: the playback service writes every stored change here, and the audio
 * thread reads the latest at the start of each buffer (a single volatile read, nothing to lock).
 */
class LiveEqualizer {
    @Volatile
    var settings: EqualizerSettings = EqualizerSettings()
}
