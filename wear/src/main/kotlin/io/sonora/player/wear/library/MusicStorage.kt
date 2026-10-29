package io.sonora.player.wear.library

import android.content.Context
import java.io.File

/**
 * Where the watch keeps its songs: `filesDir/music`, the app's own storage (no permission, removed with the app).
 * The phone's transfer (W3) writes here, and the track repository reads from here.
 */
object MusicStorage {
    const val DIRECTORY_NAME = "music"

    /** The music folder, created on first use. */
    fun directory(context: Context): File = File(context.filesDir, DIRECTORY_NAME).also { it.mkdirs() }
}
