package io.sonora.core.data.lyrics.lrclib

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.sonora.core.domain.port.OnlineLyricsSource

/** The LRCLIB lyrics service, bound only by the `full` build (the Google Play build has no online lyrics). */
object LrclibSource {
    /** LRCLIB asks clients to identify themselves: `Sonora/<versionName> (personal music player)`. */
    fun create(context: Context): OnlineLyricsSource =
        LrclibClient(UrlConnectionHttpGet("Sonora/${versionName(context)} (personal music player)"))

    private fun versionName(context: Context): String {
        val packages = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageInfo(context.packageName, 0)
        }
        return info.versionName ?: "unknown"
    }
}
