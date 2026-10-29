package io.sonora.player.wear.ui

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import kotlin.math.abs

/** The watch's music volume, which the crown sets. Only ever moved by the user's own crown turns. */
class SystemVolume(context: Context) : VolumeControl {
    private val audio = checkNotNull(context.getSystemService<AudioManager>())

    /** Moves the volume by [steps] (negative lowers it) and returns the new level in percent. */
    override fun step(steps: Int): Int {
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(abs(steps)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        return percent()
    }

    private fun percent(): Int {
        return volumePercent(
            level = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }
}
