package io.sonora.feature.nowplaying

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import androidx.annotation.RequiresApi

/** How Now Playing's audio-output button opens the system output picker, if it can. */
internal enum class OutputSwitcher {
    /** `MediaRouter2.showSystemOutputSwitcher()`, the public API (Android 14+). */
    SYSTEM_SWITCHER,

    /** The hidden settings panel `com.android.settings.panel.action.MEDIA_OUTPUT` (Android 11-13). */
    SETTINGS_PANEL,

    /** Neither: the button is not shown. */
    NONE,
}

/**
 * Which picker the output button uses on [sdk]. From Android 14 the public switcher is always preferred: the hidden
 * panel crashes MIUI's Settings on a Xiaomi Pad 6 (Android 14) and no longer resolves on Android 17. Pure, so it is
 * unit-tested.
 */
internal fun outputSwitcherFor(sdk: Int, panelResolves: Boolean): OutputSwitcher = when {
    sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> OutputSwitcher.SYSTEM_SWITCHER
    panelResolves -> OutputSwitcher.SETTINGS_PANEL
    else -> OutputSwitcher.NONE
}

/** The picker this device offers (see [outputSwitcherFor]). */
internal fun outputSwitcher(context: Context): OutputSwitcher =
    outputSwitcherFor(Build.VERSION.SDK_INT, mediaOutputPanel(context).resolveActivity(context.packageManager) != null)

/** Opens [switcher]; the public switcher falls back to the settings panel when the system declines to show it. */
internal fun showOutputSwitcher(context: Context, switcher: OutputSwitcher) {
    when (switcher) {
        OutputSwitcher.SYSTEM_SWITCHER -> if (!showSystemSwitcher(context)) startPanel(context)
        OutputSwitcher.SETTINGS_PANEL -> startPanel(context)
        OutputSwitcher.NONE -> Unit
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun showSystemSwitcher(context: Context): Boolean = MediaRouter2.getInstance(context).showSystemOutputSwitcher()

private fun startPanel(context: Context) {
    try {
        context.startActivity(mediaOutputPanel(context))
    } catch (_: ActivityNotFoundException) {
        // Not offered on this device; nothing sensible to do.
    }
}

private fun mediaOutputPanel(context: Context): Intent = Intent(MEDIA_OUTPUT_ACTION)
    .putExtra(MEDIA_OUTPUT_PACKAGE_EXTRA, context.packageName)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private const val MEDIA_OUTPUT_ACTION = "com.android.settings.panel.action.MEDIA_OUTPUT"
private const val MEDIA_OUTPUT_PACKAGE_EXTRA = "com.android.settings.panel.extra.PACKAGE_NAME"
