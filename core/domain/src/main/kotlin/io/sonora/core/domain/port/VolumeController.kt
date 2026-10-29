package io.sonora.core.domain.port

import kotlinx.coroutines.flow.StateFlow

/**
 * System music volume, normalized to 0f..1f. Reflects hardware key changes while [volume] is collected.
 */
interface VolumeController {
    val volume: StateFlow<Float>

    /** Writes the system volume; the fraction is quantized to the nearest discrete step. */
    fun setVolume(fraction: Float)

    /** Re-reads the system volume (e.g. on resume, in case a change was missed). */
    fun refresh()
}
