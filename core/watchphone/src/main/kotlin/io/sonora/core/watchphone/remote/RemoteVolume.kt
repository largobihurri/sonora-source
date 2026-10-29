package io.sonora.core.watchphone.remote

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The phone's music volume as the remote may touch it: read it, and move it by whole steps, which is all a crown turn is.
 * There is deliberately no way to set a level.
 */
interface RemoteVolume {
    fun level(): VolumeLevel

    /** Moves the volume by [steps] (negative lowers it), like that many presses of the volume keys. */
    fun step(steps: Int)
}

@Singleton
class AudioManagerRemoteVolume @Inject constructor(@ApplicationContext context: Context) : RemoteVolume {
    private val audio = checkNotNull(context.getSystemService(AudioManager::class.java))
    private val stream = AudioManager.STREAM_MUSIC

    override fun level(): VolumeLevel {
        val min = audio.getStreamMinVolume(stream)
        return VolumeLevel(
            current = (audio.getStreamVolume(stream) - min).coerceAtLeast(0),
            max = (audio.getStreamMaxVolume(stream) - min).coerceAtLeast(0),
        )
    }

    override fun step(steps: Int) {
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        // Flag 0: no volume panel on the phone; the watch shows the level.
        repeat(abs(steps)) { audio.adjustStreamVolume(stream, direction, 0) }
    }
}
